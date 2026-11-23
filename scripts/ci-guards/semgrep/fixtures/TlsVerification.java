/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Fixture for the Semgrep rules in /.semgrep.yml. Never compiled, never shipped.
package fixtures;

import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import org.apache.http.conn.ssl.NoopHostnameVerifier;

class TlsVerification {
    Object badNoopVerifier() {
        // ruleid: dlap-java-tls-verification-disabled
        return NoopHostnameVerifier.INSTANCE;
    }

    void badLambdaVerifier(HttpsURLConnection connection) {
        // ruleid: dlap-java-tls-verification-disabled
        connection.setHostnameVerifier((host, session) -> true);
    }

    SSLContext badSslContext() throws Exception {
        // ruleid: dlap-java-tls-verification-disabled
        return SSLContext.getInstance("SSL");
    }

    static class TrustEverything implements X509TrustManager {
        // ruleid: dlap-java-tls-verification-disabled
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        // ruleid: dlap-java-tls-verification-disabled
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    static class DelegatingTrust implements X509TrustManager {
        private final X509TrustManager delegate;

        DelegatingTrust(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        // ok: dlap-java-tls-verification-disabled
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        // ok: dlap-java-tls-verification-disabled
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    SSLContext goodSslContext() throws Exception {
        // ok: dlap-java-tls-verification-disabled
        return SSLContext.getInstance("TLSv1.3");
    }

    void goodVerifier(HttpsURLConnection connection) {
        // ok: dlap-java-tls-verification-disabled
        connection.setHostnameVerifier((host, session) -> host.endsWith(".example.invalid"));
    }
}
