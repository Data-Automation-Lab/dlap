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

import groovy.lang.GroovyShell;
import javax.script.ScriptEngine;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

class CodeEvaluation {
    Object badGroovy(String text) {
        // ruleid: dlap-java-code-evaluation
        GroovyShell shell = new GroovyShell();
        return shell.evaluate(text);
    }

    Object badGroovyQualified(String text) {
        // ruleid: dlap-java-code-evaluation
        groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell();
        return shell.evaluate(text);
    }

    Object badScript(ScriptEngine engine, String text) throws Exception {
        // ruleid: dlap-java-code-evaluation
        return engine.eval(text);
    }

    Object badMvel(String text) {
        // ruleid: dlap-java-code-evaluation
        return MVEL.eval(text);
    }

    Object badSpel(ExpressionParser parser, String text) {
        // ruleid: dlap-java-code-evaluation
        return parser.parseExpression(text);
    }

    Object badSpelFresh(String text) {
        // ruleid: dlap-java-code-evaluation
        return new SpelExpressionParser().parseExpression(text);
    }

    Object goodSpelLiteral(ExpressionParser parser) {
        // ok: dlap-java-code-evaluation
        return parser.parseExpression("1 + 1");
    }

    Object goodSpelFreshLiteral() {
        // ok: dlap-java-code-evaluation
        return new SpelExpressionParser().parseExpression("#root.size()");
    }
}
