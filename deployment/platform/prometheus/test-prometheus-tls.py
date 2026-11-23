#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
"""Offline checks of the TLS variant of the Prometheus configuration (Q-518).

prometheus.tls.yml is prometheus.yml with one job changed: dlap-services is scraped over HTTPS and
verified against the pilot CA. These tests keep the two in step and keep the pilot overlay wired to it:

  - without comments and blank lines, the TLS file equals the base file plus exactly the two settings
    scheme and tls_config on the dlap-services job (a new job or target in the base file that is not
    copied here fails);
  - every other job stays plain HTTP (no scheme or tls_config), so Alertmanager, Grafana and the two
    exporters keep their internal hops;
  - the TLS overlay mounts the pilot CA into Prometheus at the ca_file path, starts Prometheus with the
    TLS file, keeps the base command's other flags, and the mounted folder contains the file;
  - the services the overlay gives a TLS management port are the targets of the dlap-services job.

Run:  python3 deployment/platform/prometheus/test-prometheus-tls.py
"""

import pathlib
import re
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASE = HERE / "prometheus.yml"
TLS = HERE / "prometheus.tls.yml"
TLS_OVERLAY = ROOT / "deployment/environments/pilot/docker-compose.pilot.tls.yml"
DEV_COMPOSE = ROOT / "deployment/environments/dev/docker-compose.yml"

TLS_LINES = ["    scheme: https", "    tls_config:", "      ca_file: /tls/ca.crt"]


def significant(path):
    """The lines of a YAML file that are not comments or blank, without trailing white space."""
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            lines.append(line.rstrip())
    return lines


def jobs(lines):
    """Maps job name to its lines, for the scrape_configs section."""
    result = {}
    current = None
    in_scrape = False
    for line in lines:
        if line.startswith("scrape_configs:"):
            in_scrape = True
            continue
        if not in_scrape:
            continue
        match = re.match(r"^  - job_name: (\S+)", line)
        if match:
            current = match.group(1)
            result[current] = []
        if current:
            result[current].append(line)
    return result


def service_block(text, service):
    match = re.search(r"^  " + re.escape(service) + r":\n(.*?)(?=^  [A-Za-z0-9_-]+:\n|^[a-z]|\Z)", text, re.S | re.M)
    return match.group(1) if match else ""


class TlsPrometheusConfig(unittest.TestCase):

    def test_the_tls_file_is_the_base_file_plus_the_two_settings(self):
        base = significant(BASE)
        index = base.index("    metrics_path: /actuator/prometheus")
        expected = base[:index + 1] + TLS_LINES + base[index + 1:]
        self.assertEqual(expected, significant(TLS))

    def test_only_the_service_job_is_https(self):
        for name, lines in jobs(significant(TLS)).items():
            text = "\n".join(lines)
            if name == "dlap-services":
                self.assertIn("scheme: https", text)
                self.assertIn("ca_file: /tls/ca.crt", text)
            else:
                self.assertNotIn("scheme:", text, name)
                self.assertNotIn("tls_config", text, name)

    def test_the_base_file_stays_plain_http(self):
        text = "\n".join(significant(BASE))
        self.assertNotIn("scheme:", text)
        self.assertNotIn("tls_config", text)

    def test_the_overlay_starts_prometheus_with_the_tls_file_and_the_pilot_ca(self):
        overlay = TLS_OVERLAY.read_text(encoding="utf-8")
        block = service_block(overlay, "prometheus")
        self.assertIn("--config.file=/etc/prometheus/prometheus.tls.yml", block)
        self.assertNotIn("--config.file=/etc/prometheus/prometheus.yml", block)
        self.assertIn("pilot-tls:/tls:ro", block)
        self.assertIn("tls-init", block)
        # The mounted folder is the whole prometheus folder, so the TLS file is next to the rules.
        dev = service_block(DEV_COMPOSE.read_text(encoding="utf-8"), "prometheus")
        self.assertIn("../../platform/prometheus:/etc/prometheus:ro", dev)

    def test_the_overlay_keeps_every_other_flag_of_the_base_command(self):
        dev = service_block(DEV_COMPOSE.read_text(encoding="utf-8"), "prometheus")
        overlay = service_block(TLS_OVERLAY.read_text(encoding="utf-8"), "prometheus")
        base_flags = set(re.findall(r"- '(--[^']+)'", dev)) - {"--config.file=/etc/prometheus/prometheus.yml"}
        tls_flags = set(re.findall(r'- "(--[^"]+)"', overlay)) - {"--config.file=/etc/prometheus/prometheus.tls.yml"}
        self.assertTrue(base_flags, "no flags found in the dev compose")
        self.assertEqual(base_flags, tls_flags)

    def test_every_scraped_service_gets_a_tls_management_port(self):
        targets = set()
        for line in jobs(significant(TLS))["dlap-services"]:
            match = re.match(r"^\s+- (da-[a-z-]+):9091$", line)
            if match:
                targets.add(match.group(1))
        overlay = TLS_OVERLAY.read_text(encoding="utf-8")
        with_port = {s for s in targets
                     if 'MANAGEMENT_SERVER_SSL_CERTIFICATE: "file:/tls/%s.crt"' % s in service_block(overlay, s)}
        # da-mcp-pep is a standalone reference deployable, not part of the pilot.
        self.assertEqual(targets - {"da-mcp-pep"}, with_port)

    def test_a_service_whose_prod_profile_switches_the_export_off_has_it_switched_on_by_the_pilot(self):
        """da-discovery's application-prod.yml sets management.prometheus.metrics.export.enabled: false, so
        its /actuator/prometheus endpoint does not exist under the pilot's prod profile and its target is
        down. The pilot base overlay must set the environment variable that wins over the file."""
        base_overlay = (ROOT / "deployment/environments/pilot/docker-compose.pilot.base.yml").read_text(encoding="utf-8")
        for service in ("da-admin-service", "da-autoarchival", "da-autocompliance", "da-autodelete", "da-autolabel",
                        "da-catalog", "da-discovery", "da-logingest", "da-policyengine", "da-reporting"):
            prod = ROOT / service / "src/main/resources/config/application-prod.yml"
            if not prod.exists():
                prod = ROOT / service / "src/main/resources/application-prod.yml"
            if not prod.exists():
                continue
            text = prod.read_text(encoding="utf-8")
            if re.search(r"prometheus:\s*\n\s*metrics:\s*\n\s*export:\s*\n\s*enabled:\s*false", text):
                self.assertIn("MANAGEMENT_PROMETHEUS_METRICS_EXPORT_ENABLED=true", service_block(base_overlay, service),
                              service + " switches the Prometheus export off under prod and the pilot does not switch it on")


if __name__ == "__main__":
    unittest.main(argv=sys.argv[:1] + sys.argv[1:])
