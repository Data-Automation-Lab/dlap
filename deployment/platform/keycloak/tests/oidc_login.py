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

"""Browser sign-in against a DLAP Keycloak realm, without a browser (IAM-02).

Drives the authorization-code flow with PKCE (S256) the way the SPA does. It opens the authorization
endpoint (optionally with kc_idp_hint, so Keycloak sends it on to an identity provider), then answers
every page Keycloak shows, in whatever order the realm's flows ask for them:

  sign-in form           posts --username and --password (the identity provider's form after a hint)
  profile review         first sign-in through a provider: posts the values Keycloak filled in
  OTP setup              computes the RFC 6238 code from the secret on the page and registers the device
  OTP form               answers with --otp-secret, or stops when none is given

and exchanges the code for tokens. It never follows the final redirect to the application, so the
application need not run. Standard library only.

Prints one JSON object on stdout:

  {"outcome": "token" | "otp-setup" | "otp" | "error-page" | "unexpected",
   "steps": [...], "claims": {...} | null, "otp_secret": "..." (after a setup), "detail": "..."}

With --print-token a successful run prints {"access_token": "..."} instead.

Exit status: 0 when a token was issued, 3 when the flow stopped at an OTP step on purpose (--no-otp, or
an OTP form and no --otp-secret), 1 on any other result.
"""

import argparse
import base64
import hashlib
import hmac
import html
import http.cookiejar
import json
import re
import secrets
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

MAX_STEPS = 16


class LoopbackIsSecure(http.cookiejar.DefaultCookiePolicy):
    """Sends Secure cookies to a loopback host over plain HTTP, as browsers do.

    Keycloak 26 marks its session cookies Secure even on HTTP. Browsers treat http://localhost as a
    secure context and send them; on any other host a plain-HTTP sign-in fails with "cookie not found",
    which is why a deployment that is not reached through a loopback tunnel needs TLS.
    """

    def return_ok_secure(self, cookie, request):
        host = urllib.parse.urlparse(request.get_full_url()).hostname or ""
        if cookie.secure and host in ("localhost", "127.0.0.1", "::1"):
            return True
        return super().return_ok_secure(cookie, request)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Hands every redirect back to the caller instead of following it."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def totp(key, at=None, digits=6, period=30):
    """RFC 6238 TOTP with HMAC-SHA1, the realm's OTP policy. Keycloak's key is the UTF-8 secret string."""
    counter = int((time.time() if at is None else at) // period)
    digest = hmac.new(key.encode("utf-8"), struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % (10 ** digits)
    return str(code).zfill(digits)


def form_of(page, form_id):
    """Returns (action, {input name: value}) of the form with this id, or (None, {})."""
    match = re.search(r"<form\b[^>]*\bid=\"%s\"[^>]*>" % re.escape(form_id), page, re.S)
    if not match:
        return None, {}
    action = re.search(r"\baction=\"([^\"]*)\"", match.group(0))
    end = page.find("</form>", match.end())
    body = page[match.end():end if end >= 0 else len(page)]
    fields = {}
    for tag in re.findall(r"<input\b[^>]*>", body, re.S):
        if re.search(r"type=\"(checkbox|submit|button)\"", tag):
            continue
        name = re.search(r"\bname=\"([^\"]*)\"", tag)
        value = re.search(r"\bvalue=\"([^\"]*)\"", tag)
        if name:
            fields[html.unescape(name.group(1))] = html.unescape(value.group(1)) if value else ""
    return (html.unescape(action.group(1)) if action else None), fields


def page_message(page):
    """The first error or instruction text on a Keycloak page, for the report."""
    for pattern in (r"id=\"input-error[^\"]*\"[^>]*>\s*([^<]+)",
                    r"class=\"[^\"]*(?:kc-feedback-text|pf-v5-c-alert__title)[^\"]*\"[^>]*>\s*([^<]+)",
                    r"id=\"kc-error-message\"[^>]*>\s*<p[^>]*>\s*([^<]+)"):
        found = re.search(pattern, page, re.S)
        if found:
            return " ".join(html.unescape(found.group(1)).split())
    return "no known form on the page"


def decode_jwt(token):
    """Returns the claims of a JWT without checking the signature (the issuer is the caller's own)."""
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def main():
    parser = argparse.ArgumentParser(description="Browser sign-in against a DLAP Keycloak realm.")
    parser.add_argument("--issuer", required=True, help="realm URL, for example http://localhost:8180/realms/dlap")
    parser.add_argument("--client-id", required=True)
    parser.add_argument("--redirect-uri", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--idp-hint", help="kc_idp_hint: sign in through this identity provider")
    parser.add_argument("--otp-secret", help="answer an OTP form with codes from this secret")
    parser.add_argument("--no-otp", action="store_true", help="stop at an OTP setup instead of answering it")
    parser.add_argument("--print-token", action="store_true",
                        help='on success print {"access_token": ...} instead of the report')
    args = parser.parse_args()

    jar = http.cookiejar.CookieJar(policy=LoopbackIsSecure())
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)
    result = {"outcome": "unexpected", "steps": [], "claims": None, "detail": ""}

    def send(url, data=None):
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        try:
            response = opener.open(urllib.request.Request(url, data=body), timeout=30)
        except urllib.error.HTTPError as err:
            response = err
        return response.getcode(), response.headers, response.read().decode("utf-8", "replace")

    def report(outcome, detail="", code=1):
        result["outcome"] = outcome
        result["detail"] = detail
        print(json.dumps(result))
        return code

    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    params = {"client_id": args.client_id, "redirect_uri": args.redirect_uri, "response_type": "code",
              "scope": "openid", "state": secrets.token_hex(8), "nonce": secrets.token_hex(8),
              "code_challenge": challenge, "code_challenge_method": "S256"}
    if args.idp_hint:
        params["kc_idp_hint"] = args.idp_hint
    status, headers, page = send(args.issuer + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(params))
    signed_in = False
    for _ in range(MAX_STEPS):
        if status in (301, 302, 303):
            location = urllib.parse.urljoin(args.issuer, headers.get("Location", ""))
            if location.startswith(args.redirect_uri):
                query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
                if "code" not in query:
                    return report("error-page", "redirected back with %s" % query.get("error", ["no code"])[0])
                status, _, body = send(args.issuer + "/protocol/openid-connect/token", {
                    "grant_type": "authorization_code", "client_id": args.client_id, "code": query["code"][0],
                    "redirect_uri": args.redirect_uri, "code_verifier": verifier})
                tokens = json.loads(body)
                if status != 200 or "access_token" not in tokens:
                    return report("unexpected", "token endpoint answered %s %s" % (status, tokens.get("error")))
                result["claims"] = decode_jwt(tokens["access_token"])
                result["refresh_token_issued"] = "refresh_token" in tokens
                if args.print_token:
                    print(json.dumps({"access_token": tokens["access_token"]}))
                    return 0
                return report("token", code=0)
            status, headers, page = send(location)
            continue

        action, fields = form_of(page, "kc-form-login")
        if action:
            if signed_in:
                return report("error-page", "sign-in form shown again: " + page_message(page))
            signed_in = True
            result["steps"].append("sign-in")
            status, headers, page = send(action, dict(fields, username=args.username, password=args.password,
                                                      credentialId=""))
            continue
        action, fields = form_of(page, "kc-totp-settings-form")
        if action and fields.get("totpSecret"):
            result["steps"].append("otp-setup")
            if args.no_otp:
                return report("otp-setup", code=3)
            result["otp_secret"] = fields["totpSecret"]
            status, headers, page = send(action, dict(fields, totp=totp(fields["totpSecret"]), userLabel="smoke"))
            continue
        action, fields = form_of(page, "kc-otp-login-form")
        if action:
            if "otp" in result["steps"]:
                return report("error-page", "OTP form shown again: " + page_message(page))
            result["steps"].append("otp")
            if not args.otp_secret:
                return report("otp", code=3)
            status, headers, page = send(action, dict(fields, otp=totp(args.otp_secret)))
            continue
        for form_id in ("kc-idp-review-profile-form", "kc-update-profile-form"):
            action, fields = form_of(page, form_id)
            if action:
                result["steps"].append("profile-review")
                status, headers, page = send(action, fields)
                break
        else:
            return report("error-page", "HTTP %s: %s" % (status, page_message(page)))
    return report("unexpected", "more than %d steps" % MAX_STEPS)


if __name__ == "__main__":
    sys.exit(main())
