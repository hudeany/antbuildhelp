# AntBuildHelp

**Dependency downloads for Apache Ant builds — without Maven, Gradle or Ivy.**

[![Maven Central](https://img.shields.io/maven-central/v/de.soderer/antbuildhelp)](https://central.sonatype.com/artifact/de.soderer/antbuildhelp)
![Java](https://img.shields.io/badge/Java-17%2B-blue)
![Ant](https://img.shields.io/badge/Apache%20Ant-1.10-orange)
![License](https://img.shields.io/badge/License-MIT-green)

AntBuildHelp adds three tasks to Ant that download the jar libraries of your project, cache them in
your local Maven repository (`~/.m2/repository`) and copy them into the project's `lib/` directory,
replacing older versions there. One self-contained jar, no further libraries needed.

```xml
<getMavenDependency groupId="org.apache.poi" artifactId="poi" version="RELEASE" />
```

## Features

| | Feature | Description |
|---|---|---|
| 📦 | **Maven repositories** | Maven Central or any Maven-layout mirror, addressed by groupId/artifactId/version |
| 🔄 | **Latest version** | `version="RELEASE"` / `"LATEST"` resolved via `maven-metadata.xml` or the GitHub releases API |
| 🐙 | **GitHub releases** | Download urls with a `{version}` placeholder, latest release tag resolved automatically |
| 🔐 | **Checksum verification** | Strongest available checksum (`.sha512` > `.sha256` > `.sha1` > `.md5`) is verified |
| 🗜️ | **Zip extraction** | Extracts a jar from a downloaded zip archive, e.g. `swt.jar` from an SWT distribution |
| 🌐 | **Proxy support** | Direct proxy, PAC script or WPAD auto-detection |
| 🛡️ | **Corporate TLS proxies** | Trusts an additional certificate (e.g. Zscaler root) — certificate validation is never switched off |
| 🧹 | **Clean `lib/` directory** | Older versions of the same dependency are removed automatically |
| 💾 | **Local cache** | Maven-layout cache in `~/.m2/repository`, downloads are atomic and verified before caching |
| ⌨️ | **CLI** | Everything also works without Ant via `java -jar antbuildhelp.jar` |

## Quick start

Add these lines to your `build.xml`. The `get` and `typedef` at top level fetch AntBuildHelp itself once
and register its tasks, before any target runs. Replace `VERSION` with the version shown in the badge
above.

```xml
<project name="MyProject" default="build" basedir=".">
	<!-- Top-level tasks run in file order while the build file is parsed,
	     so "get" must come before "typedef", which needs the jar -->
	<property name="antbuildhelp.version" value="VERSION" />
	<mkdir dir="lib_build" />
	<get src="https://repo1.maven.org/maven2/de/soderer/antbuildhelp/${antbuildhelp.version}/antbuildhelp-${antbuildhelp.version}.jar"
	     dest="lib_build/antbuildhelp.jar"
	     skipexisting="true" />
	<typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />

	<target name="dependencies">
		<!-- Maven artifact from Maven Central, always the latest release -->
		<getMavenDependency groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />

		<!-- Any other download url, here a jar extracted from a zip archive -->
		<getDependency name="swt-win"
		               version="4.38-win32-win32-x86_64"
		               groupId="org.eclipse.swt"
		               url="https://archive.eclipse.org/eclipse/downloads/drops4/R-4.38-202512010920/swt-4.38-win32-win32-x86_64.zip"
		               zipEntry="swt.jar" />
	</target>

	<target name="build" depends="dependencies">
		...
	</target>
</project>
```

Alternatively, download `antbuildhelp.jar` manually from
[Maven Central](https://central.sonatype.com/artifact/de.soderer/antbuildhelp) and put it into
`lib_build/`.

## Which task for which job?

| Task | Use it for |
|---|---|
| `getMavenDependency` | Artifacts from Maven Central or a Maven-layout mirror — the shortest form for the most common case |
| `getDependency` | Any other download url: plain urls, zip archives, GitHub releases (it can do Maven artifacts too) |
| `resolveDependencies` | Several dependencies in one call, versions taken from a central `Versions.json` index file |
| `java -jar antbuildhelp.jar` | The same as `getDependency`, without Ant |

## 📦 getMavenDependency

```xml
<!-- Fixed version from Maven Central -->
<getMavenDependency groupId="org.apache.poi" artifactId="poi" version="5.2.4" />

<!-- Latest released version -->
<getMavenDependency groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />

<!-- Private Maven-layout mirror instead of Maven Central -->
<getMavenDependency repositoryUrl="https://some-repository.com/maven2"
                    groupId="de.soderer" artifactId="csv" version="26.1.1" />

<!-- Sources jar alongside the regular one -->
<getMavenDependency groupId="de.soderer" artifactId="csv" version="26.1.1" classifier="sources" />
```

The jar is named `<artifactId>-<version>[-<classifier>].jar` in `lib/` and verified against the
strongest checksum file the repository provides. `artifactId` is required; the task fails at once if
it is missing.

## 🔗 getDependency

```xml
<!-- Plain download url -->
<getDependency name="csv" version="26.1.1"
               url="https://some-repository.com/index.php?download=csv.jar" />

<!-- Jar extracted from a zip archive -->
<getDependency name="swt-win" version="4.38-win32-win32-x86_64" groupId="org.eclipse.swt"
               url="https://archive.eclipse.org/eclipse/downloads/drops4/R-4.38-202512010920/swt-4.38-win32-win32-x86_64.zip"
               zipEntry="swt.jar" />

<!-- GitHub release, latest tag resolved via the GitHub API -->
<getDependency name="somelib" version="RELEASE"
               url="https://github.com/someowner/somelib/releases/download/v{version}/somelib-{version}.jar" />

<!-- Behind a corporate proxy with TLS inspection -->
<getDependency name="csv" version="26.1.1"
               url="https://some-repository.com/index.php?download=csv.jar"
               useWpad="true"
               tlsCertificateFile="zscaler-root.cer" />

<!-- Maven artifact mode: setting artifactId makes url the repository base url -->
<getDependency url="https://some-repository.com/maven2"
               groupId="de.soderer" artifactId="csv" version="26.1.1" />
```

By default the file in `lib/` is named the way the download itself suggests (see
*useDownloadFileName* under Details below). The SWT example therefore yields
`swt-4.38-win32-win32-x86_64.jar` instead of `swt.jar`, which would collide with the Linux download
of the same entry.

## 📋 resolveDependencies

Resolves several dependencies in one call. Dependencies with version `latest` (the default) take
version and download url from a central `Versions.json`:

```xml
<resolveDependencies versionsJsonUrl="https://www.soderer.de/index.php?download=Versions.json"
                     username="myuser"
                     password="mypassword"
                     useWpad="true"
                     tlsCertificateFile="zscaler-root.cer">
	<dependency name="RestClient" />
	<dependency name="SomeThirdPartyLib" version="1.2.3"
	            url="https://example.com/libs/{name}-{version}.jar"
	            tlsCertificateFile="example-com-root.cer" />
</resolveDependencies>
```

`Versions.json` is a flat JSON object:

```json
{
	"RestClient": { "version": "26.0.82", "downloadUrl": "https://example.com/index.php?download=RestClient.jar&user=<username>&pw=<password>" }
}
```

- Download urls may contain the placeholders `{name}`, `{version}`, `{username}` and `{password}`, and
  the legacy forms `<username>` and `<password>`.
- Substituted values are url-encoded automatically, so give `username` and `password` as they are,
  not url-encoded. Special characters like `&`, `#` or `@` are safe.
- Proxy and `tlsCertificateFile` of the task apply to the `Versions.json` request and all downloads; a
  `<dependency>` may override `tlsCertificateFile` for its own download.
- The jars are placed into the local repository (`repositoryRoot`, default `~/.m2/repository`) only,
  not into `lib/`.

## ⌨️ Command line

All `getDependency` attributes are available as `--key=value` arguments; boolean flags may be given
bare (`--useWpad`). `libDir` defaults to `./lib`.

```bash
# Maven artifact, latest release
java -jar antbuildhelp.jar --groupId=com.sun.mail --artifactId=mailapi --version=RELEASE

# Sources jar
java -jar antbuildhelp.jar --groupId=de.soderer --artifactId=csv --version=26.1.1 --classifier=sources

# GitHub release, latest tag
java -jar antbuildhelp.jar --name=somelib --version=RELEASE \
    --url=https://github.com/someowner/somelib/releases/download/v{version}/somelib-{version}.jar

# Plain url behind a corporate proxy
java -jar antbuildhelp.jar --name=csv --version=26.1.1 --libDir=lib \
    --url=https://some-repository.com/index.php?download=csv.jar \
    --useWpad --tlsCertificateFile=zscaler-root.cer
```

`java -jar antbuildhelp.jar --help` shows the full option list. The exit code is `0` on success and
`1` on any error.

## Attribute reference

`getDependency` / CLI and `getMavenDependency` share most attributes:

| Attribute | getDependency / CLI | getMavenDependency | Default | Description |
|---|:---:|:---:|---|---|
| `version` | ✅ required | ✅ required | | Version, or `RELEASE` / `LATEST` |
| `url` | ✅ | | | Full download url; repository base url in Maven artifact mode |
| `repositoryUrl` | | ✅ | Maven Central | Maven-layout repository base url |
| `artifactId` | ✅ | ✅ required | | Maven artifactId, switches `getDependency` into Maven artifact mode |
| `groupId` | ✅ | ✅ | `de.soderer` | Maven groupId, also used for the local cache path |
| `classifier` | ✅ | ✅ | | Maven classifier, e.g. `sources` or `javadoc` |
| `name` | ✅ | ✅ | artifactId | Logical name for the local cache and the default file name |
| `libDir` | ✅ | ✅ | `<basedir>/lib` | Target directory, relative to the project's basedir |
| `repositoryRoot` | ✅ | ✅ | `~/.m2/repository` | Local repository cache |
| `zipEntry` | ✅ | | | Entry to extract from a downloaded zip archive |
| `useDownloadFileName` | ✅ | | `true` | Name the file as the download suggests |
| `proxyUrl` | ✅ | ✅ | | Direct proxy, e.g. `http://proxy:8080` (scheme optional, port default 80) |
| `pacUrl` | ✅ | ✅ | | PAC script url |
| `useWpad` | ✅ | ✅ | `false` | Auto-detect the PAC script via WPAD |
| `tlsCertificateFile` | ✅ | ✅ | | Additionally trusted certificate (PEM or DER), relative to the basedir |

`resolveDependencies` supports `repositoryRoot`, `groupId`, `versionsJsonUrl`, `username`, `password`,
`proxyUrl`, `pacUrl`, `useWpad` and `tlsCertificateFile`; nested `<dependency>` elements support
`name` (required), `version` (default `latest`), `url` and `tlsCertificateFile`.

## Details

<details>
<summary><b>Maven artifact mode</b></summary>

Setting `artifactId` (always the case for `getMavenDependency`) switches into Maven artifact mode:

- **Download url** is derived as `<base>/<groupId as path>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].jar`.
  The base is `url` / `repositoryUrl`, or Maven Central (`https://repo1.maven.org/maven2/`) if not set.
- **`groupId`** must be set to the artifact's real groupId — the default `de.soderer` will not resolve
  anywhere else.
- **`version="RELEASE"` / `"LATEST"`** (case-insensitive) is resolved first via
  `<groupId>/<artifactId>/maven-metadata.xml`. The resolved version is then used everywhere: download
  url, cache path, file name and checksum.
- **File name** in `lib/` is always `<artifactId>-<version>[-<classifier>].jar`. `name` only affects
  the local cache path; with a classifier it defaults to `<artifactId>-<classifier>`, so the classified
  jar gets its own cache entry.
- **Checksums** are always verified, see below.

</details>

<details>
<summary><b>GitHub releases</b></summary>

Without `artifactId`, `url` may contain a `{version}` placeholder that is substituted with `version`.

If `url` is a `github.com` releases-download url (`https://github.com/<owner>/<repo>/releases/download/...`)
and `version` is `RELEASE` or `LATEST`, the actual tag is resolved first via
`https://api.github.com/repos/<owner>/<repo>/releases/latest`. A leading `v` directly followed by a
digit is stripped (`v1.2.3` → `1.2.3`), so the tag can be used as plain version in the url template.

Downloads from GitHub releases are checksum-verified like Maven artifacts, if the release provides a
checksum asset named like the jar plus `.sha512`, `.sha256`, `.sha1` or `.md5`.

Note that the GitHub API allows only a limited number of unauthenticated requests per hour. If the
limit is reached, the error message shows the HTTP status (403).

</details>

<details>
<summary><b>useDownloadFileName</b></summary>

Only without `artifactId`. If `true` (the default), the file in `lib/` is named the way the download
suggests, instead of `<name>-<version>.jar`:

1. the server's `Content-Disposition` response header, if one is sent
2. else the last path segment of `url`, if it ends in `.jar` or `.zip`
3. else `<name>-<version>.jar`

With `zipEntry`, the name of the downloaded **zip archive** is used with `.zip` swapped for `.jar`, not
the name of the extracted entry, which is often identical across platform-specific downloads.

Set `useDownloadFileName="false"` (CLI: `--useDownloadFileName=false`) to always get
`<name>-<version>.jar`.

Two things to know:

- If the jar is already in the local cache, no request is made, so only option 2 is available. A
  `Content-Disposition` name from an earlier run is not remembered.
- Old versions are found by the pattern `<name>-*.jar`. A download-derived file name that does not
  start with `<name>-` is therefore not cleaned up automatically — an extra stale jar, never a wrongly
  deleted one.

</details>

<details>
<summary><b>Proxy and TLS</b></summary>

- **Proxy precedence** (set at most one): `proxyUrl` > `pacUrl` > `useWpad`. PAC scripts are evaluated
  per download url, so a PAC script may decide `DIRECT` for some hosts.
- **TLS certificate validation can never be switched off.** For a corporate TLS-inspecting proxy such as
  Zscaler, trust its root certificate in addition to the JVM's default CAs via `tlsCertificateFile`
  (X.509, PEM or DER).
- Redirects are followed, except from `https` to `http`.

</details>

<details>
<summary><b>Integrity and caching</b></summary>

- Downloads are written to temporary files first and moved into the local cache only after a
  successful checksum verification (and zip extraction). An interrupted or tampered download never
  ends up in the cache.
- A file present in the cache is trusted without being verified again, so a dependency is downloaded
  only once per machine.
- The checksum verification uses the strongest checksum file available next to the download:
  `.sha512` > `.sha256` > `.sha1` > `.md5`. A mismatch fails the build; if no checksum file exists, a
  warning is logged.
- Plain url downloads without `artifactId` (other than GitHub releases) are not checksum-verified,
  because there is no standard place for a checksum file.

</details>

## Use as a library

The resolution logic is independent of Ant and can be used from Java code:

```xml
<dependency>
	<groupId>de.soderer</groupId>
	<artifactId>antbuildhelp</artifactId>
	<version>VERSION</version>
</dependency>
```

```groovy
implementation "de.soderer:antbuildhelp:VERSION"
```

```java
final Path jarFile = new DependencyResolver()
		.withGroupId("com.sun.mail")
		.withArtifactId("mailapi")
		.withVersion("RELEASE")
		.withLibDir(Paths.get("lib"))
		.withLogger(System.out::println)
		.resolve();
```

A `DependencyResolver` instance is meant for a single `resolve()` call.

<details>
<summary><b>Package overview</b></summary>

| Class | Purpose |
|---|---|
| `DependencyResolver` | The actual resolution logic: download, cache, checksum verification, zip extraction, old-version cleanup. No dependency on Ant. |
| `GetDependencyTask` | Ant task `getDependency`, thin wrapper around `DependencyResolver` |
| `GetMavenDependencyTask` | Ant task `getMavenDependency`, the same restricted to Maven artifact mode |
| `ResolveDependenciesTask` | Ant task `resolveDependencies`, own implementation based on `Versions.json` |
| `AntBuildHelpMain` | CLI entry point (`Main-Class` of the jar) |
| `download.HttpClientFactory` | Creates the HTTP clients for all tasks, so proxy and TLS handling is identical everywhere |
| `antlib.xml` | Registers all three tasks with a single `typedef` |

The classes of AntBuildHelp's own dependencies, [json](https://github.com/hudeany/json) and
[ProxyAutoConfig](https://github.com/hudeany/ProxyAutoConfig), are embedded into the jar. That is why
a single jar is enough for `typedef`, `java -jar` and Maven consumers alike, and why the Maven POM
lists no dependencies.

</details>

## Building from source

```bash
ant
```

The build downloads all needed libraries, compiles, runs the tests and creates in `build/`:

| File | Content |
|---|---|
| `antbuildhelp-<version>.jar` | Classes including the embedded json and ProxyAutoConfig classes; signed if `~/git/codeSigning.properties` exists |
| `antbuildhelp-<version>-sources.jar` | Sources |
| `antbuildhelp-<version>-javadoc.jar` | Javadoc |

Each with `.md5` and `.sha1` checksum files. The version is read from `build.version`, which is
written by the BuildAndPublish tool; without it, `26.0.0` is used.

- Sources follow the Maven standard directory layout (`src/main/java`, `src/main/resources`,
  `src/test/java`).
- `lib/` holds json and ProxyAutoConfig (embedded), `lib_ant/` holds `ant.jar` and
  `ant-launcher.jar` (build time only), `lib_test/` the JUnit console launcher.
- The JUnit 5 tests run against the finished, still unsigned jar, so the embedded classes are tested
  too. They use a local HTTP/HTTPS server and need no internet access; the certificate for the HTTPS
  tests is generated at test runtime with the JDK's `keytool`, and the real `~/.m2/repository` is never
  touched.
- A failing test stops the build before the jar is signed.

## Known limitations

- No dependencies file (YAML/JSON/XML) yet — one task call or CLI invocation per library.
- `resolveDependencies` does not verify checksums, unlike `getDependency` and `getMavenDependency`.
- A `Content-Disposition`-derived file name is not remembered in the local cache, so another project
  taking the same dependency from the cache may name the file differently.

## License

[MIT](LICENSE.txt) © Andreas Soderer
