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
// "// ruleid: <id>" marks the next line as a required finding; "// ok: <id>" as a forbidden one.
package fixtures;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.beans.XMLDecoder;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class NativeDeserialization {
    Object bad(InputStream in) throws Exception {
        // ruleid: dlap-java-native-deserialization
        ObjectInputStream stream = new ObjectInputStream(in);
        return stream.readObject();
    }

    Object badQualified(InputStream in) throws Exception {
        // ruleid: dlap-java-native-deserialization
        java.io.ObjectInputStream stream = new java.io.ObjectInputStream(in);
        return stream.readObject();
    }

    Object badXml(InputStream in) {
        // ruleid: dlap-java-native-deserialization
        XMLDecoder decoder = new XMLDecoder(in);
        return decoder.readObject();
    }

    Object badYamlDefault(String text) {
        // ruleid: dlap-java-native-deserialization
        Yaml yaml = new Yaml();
        return yaml.load(text);
    }

    Object badYamlOptionsOnly(String text, LoaderOptions options) {
        // ruleid: dlap-java-native-deserialization
        Yaml yaml = new Yaml(options);
        return yaml.load(text);
    }

    Object goodYaml(String text, LoaderOptions options) {
        // ok: dlap-java-native-deserialization
        Yaml yaml = new Yaml(new SafeConstructor(options));
        return yaml.load(text);
    }

    Object goodBytes() {
        // ok: dlap-java-native-deserialization
        return new ByteArrayInputStream(new byte[0]);
    }
}
