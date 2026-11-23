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

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

class WeakCipher {
    Cipher badEcb() throws Exception {
        // ruleid: dlap-java-weak-cipher
        return Cipher.getInstance("AES/ECB/PKCS5Padding");
    }

    Cipher badQualified() throws Exception {
        // ruleid: dlap-java-weak-cipher
        return javax.crypto.Cipher.getInstance("DES");
    }

    Cipher badBareAes() throws Exception {
        // ruleid: dlap-java-weak-cipher
        return Cipher.getInstance("AES");
    }

    Cipher badDes() throws Exception {
        // ruleid: dlap-java-weak-cipher
        return Cipher.getInstance("DESede/CBC/PKCS5Padding");
    }

    Cipher badRc4() throws Exception {
        // ruleid: dlap-java-weak-cipher
        return Cipher.getInstance("RC4");
    }

    Cipher goodGcm() throws Exception {
        // ok: dlap-java-weak-cipher
        return Cipher.getInstance("AES/GCM/NoPadding");
    }

    SecretKeySpec goodKeySpecNamesAes(byte[] key) {
        // ok: dlap-java-weak-cipher
        return new SecretKeySpec(key, "AES");
    }
}
