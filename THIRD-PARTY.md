# Third-party notices

The Email MCP Server is licensed under the BSD 3-Clause License (see [LICENSE](LICENSE)). The release
artifacts, the uber-jar and the native binaries, also contain the libraries listed below. They stay under
their own licenses, which are reproduced or linked here as those licenses require. Each library is listed
once, under the license it is taken under; where the upstream project offers a choice, the alternatives are
noted.

The list covers the runtime dependencies of the current build. It was produced with

```bash
mvn org.codehaus.mojo:license-maven-plugin:2.5.0:add-third-party -Dlicense.excludedScopes=test,provided
```

and hand-grouped by license. Regenerate it after changing dependencies. Build-time only tools (GraalVM,
Quarkus build steps, the Maven plugins) are not part of the shipped artifacts and are left out.

## Summary

| License | Libraries |
|---|---|
| Apache License 2.0 | Quarkus, SmallRye, Vert.x, Netty, Jackson, JBoss Logging, MicroProfile and the CDI APIs |
| Eclipse Distribution License 1.0 (BSD 3-Clause) | Angus Mail, Angus Activation, Jakarta Mail API, Jakarta Activation API |
| Eclipse Public License 2.0 | Jakarta Annotations, Interceptors, JSON Processing, Expression Language, Transaction APIs; Eclipse Parsson |
| MIT | jsoup, SLF4J API |
| BSD 2-Clause | org.crac |
| Bouncy Castle Licence | Bouncy Castle provider, PKIX and utility modules |
| GNU Affero General Public License v3.0 | iText 7 kernel, io and commons |

### A note on iText

PDF text extraction uses [iText 7](https://itextpdf.com/) version 7.2.6, which its authors publish under the
GNU Affero General Public License v3.0 (AGPL). The AGPL is a copyleft license: it grants its permissions on
the condition that a work which incorporates the library is, when distributed, made available under the
AGPL as a whole, with its complete corresponding source. The iText source is available from
https://github.com/itext/itext-java and the complete source of this server from
https://github.com/thegreystone/mcp-email. Anyone redistributing the release artifacts, or building a
product on them, should read the AGPL and iText's own licensing information and satisfy themselves that
their use complies; iText also sells a commercial license for those who cannot accept the AGPL terms.

## Full list

### Apache License 2.0

https://www.apache.org/licenses/LICENSE-2.0

| Artifact | Version | Name |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-annotations` | 2.22 | [Jackson-annotations](https://github.com/FasterXML/jackson) |
| `com.fasterxml.jackson.core:jackson-core` | 2.22.0 | [Jackson-core](https://github.com/FasterXML/jackson-core) |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.0 | [jackson-databind](https://github.com/FasterXML/jackson) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.0 | [Jackson datatype: jdk8](https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jdk8) |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.22.0 | [Jackson datatype: JSR310](https://github.com/FasterXML/jackson-modules-java8/jackson-datatype-jsr310) |
| `com.fasterxml.jackson.module:jackson-module-parameter-names` | 2.22.0 | [Jackson-module-parameter-names](https://github.com/FasterXML/jackson-modules-java8/jackson-module-parameter-names) |
| `com.fasterxml:classmate` | 1.7.1 | [ClassMate](https://github.com/FasterXML/java-classmate) |
| `com.github.victools:jsonschema-generator` | 4.38.0 | [Java JSON Schema Generator](https://github.com/victools/jsonschema-generator) |
| `io.netty:netty-buffer` | 4.1.136.Final | [Netty/Buffer](https://netty.io/netty-buffer/) |
| `io.netty:netty-codec` | 4.1.136.Final | [Netty/Codec](https://netty.io/netty-codec/) |
| `io.netty:netty-codec-dns` | 4.1.136.Final | [Netty/Codec/DNS](https://netty.io/netty-codec-dns/) |
| `io.netty:netty-codec-haproxy` | 4.1.136.Final | [Netty/Codec/HAProxy](https://netty.io/netty-codec-haproxy/) |
| `io.netty:netty-codec-http` | 4.1.136.Final | [Netty/Codec/HTTP](https://netty.io/netty-codec-http/) |
| `io.netty:netty-codec-http2` | 4.1.136.Final | [Netty/Codec/HTTP2](https://netty.io/netty-codec-http2/) |
| `io.netty:netty-codec-socks` | 4.1.136.Final | [Netty/Codec/Socks](https://netty.io/netty-codec-socks/) |
| `io.netty:netty-common` | 4.1.136.Final | [Netty/Common](https://netty.io/netty-common/) |
| `io.netty:netty-handler` | 4.1.136.Final | [Netty/Handler](https://netty.io/netty-handler/) |
| `io.netty:netty-handler-proxy` | 4.1.136.Final | [Netty/Handler/Proxy](https://netty.io/netty-handler-proxy/) |
| `io.netty:netty-resolver` | 4.1.136.Final | [Netty/Resolver](https://netty.io/netty-resolver/) |
| `io.netty:netty-resolver-dns` | 4.1.136.Final | [Netty/Resolver/DNS](https://netty.io/netty-resolver-dns/) |
| `io.netty:netty-tcnative-classes` | 2.0.78.Final | [Netty/TomcatNative [OpenSSL - Classes]](https://github.com/netty/netty-tcnative/netty-tcnative-classes/) |
| `io.netty:netty-transport` | 4.1.136.Final | [Netty/Transport](https://netty.io/netty-transport/) |
| `io.netty:netty-transport-native-unix-common` | 4.1.136.Final | [Netty/Transport/Native/Unix/Common](https://netty.io/netty-transport-native-unix-common/) |
| `io.quarkiverse.mcp:quarkus-mcp-server-core` | 1.13.1 | [Quarkus MCP Server Core - Runtime](https://quarkiverse.io) |
| `io.quarkiverse.mcp:quarkus-mcp-server-stdio` | 1.13.1 | [Quarkus MCP Server Transport - stdio - Runtime](https://quarkiverse.io) |
| `io.quarkus.arc:arc` | 3.38.0 | [ArC - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus.security:quarkus-security` | 2.3.2 | [Quarkus Security API](http://www.jboss.org) |
| `io.quarkus:quarkus-arc` | 3.38.0 | [Quarkus - ArC - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-bootstrap-runner` | 3.38.0 | [Quarkus - Bootstrap - Runner](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-classloader-commons` | 3.38.0 | [Quarkus - Bootstrap - Classloader common utilities](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-core` | 3.38.0 | [Quarkus - Core - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-development-mode-spi` | 3.38.0 | [Quarkus - Development mode - SPI](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-fs-util` | 1.4.2 | [Quarkus - FS Util](https://quarkus.io/) |
| `io.quarkus:quarkus-ide-launcher` | 3.38.0 | [Quarkus - IDE Launcher](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-jackson` | 3.38.0 | [Quarkus - Jackson - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-mutiny` | 3.38.0 | [Quarkus - Mutiny - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-netty` | 3.38.0 | [Quarkus - Netty - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-security-runtime-spi` | 3.38.0 | [Quarkus - Security - Runtime SPI](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-smallrye-context-propagation` | 3.38.0 | [Quarkus - SmallRye Context Propagation - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-value-registry` | 3.38.0 | [Quarkus - Value Registry](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-vertx` | 3.38.0 | [Quarkus - Vert.x - Runtime](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-vertx-latebound-mdc-provider` | 3.38.0 | [Quarkus - Vert.x Late Bound MDC Provider](https://github.com/quarkusio/quarkus) |
| `io.quarkus:quarkus-virtual-threads` | 3.38.0 | [Quarkus - Virtual Threads - Runtime](https://github.com/quarkusio/quarkus) |
| `io.smallrye.common:smallrye-common-annotation` | 2.19.0 | [SmallRye Common: Annotations](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-classloader` | 2.19.0 | [SmallRye Common: Classloader](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-constraint` | 2.19.0 | [SmallRye Common: Constraints](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-cpu` | 2.19.0 | [SmallRye Common: CPU](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-expression` | 2.19.0 | [SmallRye Common: Expressions](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-function` | 2.19.0 | [SmallRye Common: Functions](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-io` | 2.19.0 | [SmallRye Common: IO](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-net` | 2.19.0 | [SmallRye Common: Net](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-os` | 2.19.0 | [SmallRye Common: OS](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-ref` | 2.19.0 | [SmallRye Common: References](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-search` | 2.19.0 | [SmallRye Common: Search](http://smallrye.io) |
| `io.smallrye.common:smallrye-common-vertx-context` | 2.19.0 | [SmallRye Common: Vert.x 4 Context Utilities](http://smallrye.io) |
| `io.smallrye.config:smallrye-config` | 3.17.2 | [SmallRye Config: CDI](https://smallrye.io) |
| `io.smallrye.config:smallrye-config-common` | 3.17.2 | [SmallRye Config: Common](https://smallrye.io) |
| `io.smallrye.config:smallrye-config-core` | 3.17.2 | [SmallRye Config: Core](https://smallrye.io) |
| `io.smallrye.reactive:mutiny` | 3.3.0 | [SmallRye Mutiny - Core library](https://smallrye.io/smallrye-mutiny) |
| `io.smallrye.reactive:mutiny-smallrye-context-propagation` | 3.3.0 | [SmallRye Mutiny - Integration with SmallRye Context Propagation](https://smallrye.io/smallrye-mutiny) |
| `io.smallrye.reactive:smallrye-mutiny-vertx-core` | 3.23.0 | [SmallRye Mutiny - Vert.x Core](https://smallrye.io/smallrye-mutiny-vertx-bindings) |
| `io.smallrye.reactive:smallrye-mutiny-vertx-runtime` | 3.23.0 | [SmallRye Mutiny - Runtime Helpers](https://smallrye.io/smallrye-mutiny-vertx-bindings) |
| `io.smallrye:smallrye-context-propagation` | 2.3.0 | [SmallRye Context Propagation: Core](https://github.com/smallrye/smallrye-context-propagation) |
| `io.smallrye:smallrye-context-propagation-api` | 2.3.0 | [SmallRye Context Propagation: API](https://github.com/smallrye/smallrye-context-propagation) |
| `io.smallrye:smallrye-context-propagation-storage` | 2.3.0 | [SmallRye Context Propagation: Storage](https://github.com/smallrye/smallrye-context-propagation) |
| `io.smallrye:smallrye-fault-tolerance-vertx` | 6.11.2 | [SmallRye Fault Tolerance: Vert.x Integration](https://smallrye.io) |
| `io.vertx:vertx-core` | 4.5.30 | [Vert.x Core](https://github.com/vert-x3/vertx-parent/vertx-core) |
| `jakarta.enterprise:jakarta.enterprise.cdi-api` | 4.1.0 | [CDI APIs](http://cdi-spec.org) |
| `jakarta.enterprise:jakarta.enterprise.lang-model` | 4.1.0 | [CDI Language Model](https://projects.eclipse.org/projects/ee4j/jakarta.enterprise.cdi-parent/jakarta.enterprise.lang-model) |
| `jakarta.inject:jakarta.inject-api` | 2.0.1 | [Jakarta Dependency Injection](https://github.com/eclipse-ee4j/injection-api) |
| `org.eclipse.microprofile.config:microprofile-config-api` | 3.1.1 | [MicroProfile Config API](https://microprofile.io/project/eclipse/microprofile-config/microprofile-config-api) |
| `org.eclipse.microprofile.context-propagation:microprofile-context-propagation-api` | 1.3 | [MicroProfile Context Propagation](http://microprofile.io/microprofile-context-propagation-api) |
| `org.jboss.logging:jboss-logging` | 3.6.3.Final | [JBoss Logging 3](https://www.jboss.org) |
| `org.jboss.logmanager:jboss-logmanager` | 3.2.2.Final | [JBoss Log Manager](http://www.jboss.org) |
| `org.jboss.slf4j:slf4j-jboss-logmanager` | 2.0.2.Final | [SLF4J: JBoss Log Manager](http://www.jboss.org) |
| `org.jboss.threads:jboss-threads` | 3.9.2 | [JBoss Threads](https://github.com/jbossas/jboss-threads) |
| `org.jctools:jctools-core` | 4.0.5 | [Java Concurrency Tools Core Library](https://github.com/JCTools) |
| `org.wildfly.common:wildfly-common` | 2.0.1 | [wildfly-common](http://www.jboss.org) |


### MIT License

https://opensource.org/license/mit

| Artifact | Version | Name |
|---|---|---|
| `org.jsoup:jsoup` | 1.23.1 | [jsoup Java HTML Parser](https://jsoup.org/) |
| `org.slf4j:slf4j-api` | 2.0.18 | [SLF4J API Module](http://www.slf4j.org) |


### BSD 2-Clause License

https://opensource.org/license/bsd-2-clause

| Artifact | Version | Name |
|---|---|---|
| `org.crac:crac` | 1.5.0 | [crac](https://github.com/crac/org.crac) |


### Eclipse Distribution License 1.0 (BSD 3-Clause)

https://www.eclipse.org/org/documents/edl-v10.php

Also offered under EPL 2.0 or GPL 2.0 with Classpath Exception where noted; taken here under the EDL.

| Artifact | Version | Name |
|---|---|---|
| `jakarta.activation:jakarta.activation-api` | 2.1.4 | [Jakarta Activation API](https://github.com/jakartaee/jaf-api) |
| `jakarta.mail:jakarta.mail-api` | 2.1.5 | [Jakarta Mail API](https://projects.eclipse.org/projects/ee4j/jakarta.mail-api) |
| `org.eclipse.angus:angus-activation` | 2.0.3 | [Angus Activation Registries](https://github.com/eclipse-ee4j/angus-activation/angus-activation) |
| `org.eclipse.angus:angus-mail` | 2.0.5 | [Angus Mail Provider](http://eclipse-ee4j.github.io/angus-mail/angus-mail) |


### Bouncy Castle Licence (MIT-style)

https://www.bouncycastle.org/licence.html

| Artifact | Version | Name |
|---|---|---|
| `org.bouncycastle:bcpkix-jdk18on` | 1.84 | [Bouncy Castle PKIX, CMS, EAC, TSP, PKCS, OCSP, CMP, and CRMF APIs](https://www.bouncycastle.org/download/bouncy-castle-java/) |
| `org.bouncycastle:bcprov-jdk18on` | 1.84 | [Bouncy Castle Provider](https://www.bouncycastle.org/download/bouncy-castle-java/) |
| `org.bouncycastle:bcutil-jdk18on` | 1.84 | [Bouncy Castle ASN.1 Extension and Utility APIs](https://www.bouncycastle.org/download/bouncy-castle-java/) |


### Eclipse Public License 2.0

https://www.eclipse.org/legal/epl-2.0/

Also offered under GPL 2.0 with Classpath Exception where noted; taken here under the EPL.

| Artifact | Version | Name |
|---|---|---|
| `jakarta.annotation:jakarta.annotation-api` | 3.0.0 | [Jakarta Annotations API](https://projects.eclipse.org/projects/ee4j.ca) |
| `jakarta.el:jakarta.el-api` | 6.0.1 | [Jakarta Expression Language API](https://projects.eclipse.org/projects/ee4j.el) |
| `jakarta.interceptor:jakarta.interceptor-api` | 2.2.0 | [Jakarta Interceptors](https://github.com/jakartaee/interceptors) |
| `jakarta.json:jakarta.json-api` | 2.1.3 | [Jakarta JSON Processing API](https://github.com/eclipse-ee4j/jsonp) |
| `jakarta.transaction:jakarta.transaction-api` | 2.0.1 | [jakarta.transaction API](https://projects.eclipse.org/projects/ee4j.jta) |
| `org.eclipse.parsson:parsson` | 1.1.9 | [Eclipse Parsson](https://github.com/eclipse-ee4j/parsson/parsson) |


### GNU Affero General Public License v3.0

https://www.gnu.org/licenses/agpl-3.0.html

| Artifact | Version | Name |
|---|---|---|
| `com.itextpdf:commons` | 7.2.6 | [iText 7 - commons](https://itextpdf.com/) |
| `com.itextpdf:io` | 7.2.6 | [iText 7 - io](https://itextpdf.com/) |
| `com.itextpdf:kernel` | 7.2.6 | [iText 7 - kernel](https://itextpdf.com/) |
