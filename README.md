# AntBuildHelp

Includes ANT tasks:
- getDependency
  for general download of dependency jar libs
- getMavenDependency
  for download of dependency jar libs from a MAVEN2 repository
- resolveDependencies
  for download of dependency jar libs by special index jsons files (Versions.json),
  several dependencies per task call

`getDependency` and `getMavenDependency` each download a single dependency jar into a local
Maven-layout-compatible repository cache, then copy it into a target `lib/` directory,
removing any older version of the same dependency found there first.
Usable both as an ANT task and as a standalone CLI (`java -jar antbuildhelp.jar ...`).

antbuildhelp.jar is self-contained: the classes of its own dependencies (`json` and
`proxyautoconfig`) are embedded directly into the jar, so a single jar on the `typedef`
classpath or on the `java -jar` command line is all that is needed.

## Package overview

- `DependencyResolver` — the actual resolution logic (download, cache, old-version
  cleanup, zip-entry extraction, checksum verification). No dependency on
  org.apache.tools.ant.*.
- `download.HttpClientFactory` — creates the HTTP clients for all tasks and the
  CLI, so proxy (proxyUrl/PACURL/WPAD via the embedded proxyautoconfig classes)
  and TLS certificate trust are handled identically everywhere.
- `GetDependencyTask` — thin ANT task wrapper (`de.soderer.antbuildhelp.GetDependencyTask`,
  registered as `getDependency` in `antlib.xml`) around `DependencyResolver`. Covers
  plain URL downloads (optionally zip-extracted), GitHub releases, and Maven
  artifact mode (via its own `artifactId` attribute).
- `GetMavenDependencyTask` — thin ANT task wrapper (`de.soderer.antbuildhelp.GetMavenDependencyTask`,
  registered as `getMavenDependency` in `antlib.xml`) around the same
  `DependencyResolver`, scoped to Maven artifact mode only. Shorter and clearer
  for the common case of a pure Maven Central (or Maven-layout-mirror)
  dependency — no plain-URL-related attributes, and `artifactId` is required
  (fails fast instead of silently falling back to a literal-URL download if
  forgotten). See "Maven artifact mode" below; everything documented there for
  `getDependency`'s Maven mode applies here too, just with `url` renamed to
  `repositoryUrl` for clarity (it can never be a literal download url in this
  task).
- `AntBuildHelpMain` — standalone CLI entry point (jar's Main-Class), same
  attributes as `getDependency` (including Maven mode via `--artifactId`),
  given as `--key=value` arguments. Run with `--help` for the full option
  list. (There's no separate CLI mode mirroring `getMavenDependency` — the
  existing `--artifactId` flags already cover that case for the CLI.)
- `antlib.xml` — registers the `getDependency`, `getMavenDependency` and
  `resolveDependencies` tasks; import all of them in one line with
  `<typedef resource="de/soderer/antbuildhelp/antlib.xml" .../>`.
- `ResolveDependenciesTask` — ANT task (`de.soderer.antbuildhelp.ResolveDependenciesTask`,
  registered as `resolveDependencies` in `antlib.xml`) resolving several
  dependencies per call via a Versions.json index file into the local repository
  (own implementation, not based on `DependencyResolver`, but using the same
  `HttpClientFactory`).

## Attributes / CLI options

`version` (always required); `url` and/or `artifactId` (at least one required —
see "Maven artifact mode" below); `name` (required unless `artifactId` is set,
see below); `groupId` (default `de.soderer`), `libDir` (default
`<project dir>/lib` for the ANT task, `./lib` for the CLI), `repositoryRoot`
(default `~/.m2/repository`), `proxyUrl`, `pacUrl`, `useWpad`,
`tlsCertificateFile`, `zipEntry`, `useDownloadFileName` (optional).

Proxy resolution precedence (set at most one): `proxyUrl` > `pacUrl` > `useWpad`.

TLS certificate validation can never be switched off. For a corporate
TLS-inspecting proxy (e.g. Zscaler), its root certificate is trusted in
addition to the JVM's default CAs via `tlsCertificateFile` (X.509, PEM or DER;
relative paths are resolved against the project's basedir in the ANT tasks).

### `useDownloadFileName` (default: `true`)

Default mode only (no `artifactId`): names the `libDir` target file as the
download itself specifies, instead of the usual `<name>-<version>.jar`.
Preference order:

1. The server's `Content-Disposition` response header, if one is sent
   (handles URLs where the path gives no real filename, e.g.
   `.../index.php?download=csv.jar`).
2. The last path segment of `url` (query string ignored), if it ends in
   `.jar` or `.zip`.
3. Falls back to `<name>-<version>.jar` if neither of the above yields
   anything usable — which for a query-string-only url like
   `.../index.php?download=csv.jar` (no `Content-Disposition` sent) is exactly
   what happens anyway, so this default changes nothing there.

With `zipEntry` set, the name is taken from the downloaded **zip archive**
itself (not the extracted entry — often identically named across several
platform-specific downloads, e.g. SWT's `swt.jar` for both Windows and
Linux), with a trailing `.zip` swapped for `.jar` — so SWT's Windows download
gets named `swt-4.38-win32-win32-x86_64.jar` instead of `swt-win-4.38-...jar`.

Set `useDownloadFileName="false"` (ANT) / `--useDownloadFileName=false` (CLI)
to always get `<name>-<version>.jar`, regardless of what the download itself
suggests.

Two things to know:
- If the repository cache already has the file from a previous run, no
  request is made this run, so only option 2 is available then — the
  `Content-Disposition`-derived name from a past run isn't remembered.
- `removeOldVersions` still globs old candidates by `name + "-*.jar"`. With a
  download-derived filename that doesn't happen to start with `name-`, an old
  version won't be found and cleaned up automatically — a stale extra jar in
  `libDir`, not a wrongly deleted one, so this fails safe rather than silent
  data loss.

### Maven artifact mode

Setting `artifactId` switches a dependency into Maven artifact mode:

- **`name`** falls back to `artifactId` if not explicitly set. In this mode
  `name` only affects the local repository cache path — the `libDir` file is
  always named `<artifactId>-<version>[-<classifier>].jar`.
- **`url`**, if given, is no longer the full download URL — it's treated as the
  Maven-layout repository *base* URL (e.g. a private mirror such as
  `https://some-repository.com/maven2`). If omitted, Maven Central
  (`https://repo1.maven.org/maven2/`) is used as the base. Either way, the full
  artifact path (`groupId/artifactId/version/artifactId-version.jar`) is always
  derived and appended.
- **`groupId`** must be set to the artifact's real Maven groupId in this mode —
  the default `de.soderer` won't resolve against Maven Central or most other
  repositories.
- **`version`** may be `RELEASE` or `LATEST` (case-insensitive) instead of an
  explicit version number: the actual version is resolved beforehand via the
  repository's `<groupId>/<artifactId>/maven-metadata.xml` (`<release>` /
  `<latest>` tag), and that resolved version is then used everywhere — cache
  path, `libDir` filename, download URL, checksum.
- The download is verified against the strongest checksum file available next
  to it, in order `.sha512` > `.sha256` > `.sha1` > `.md5`. A mismatch fails the
  build; if none of the four exist, only a warning is logged (no hard failure).
  Downloads are written to temporary files first and only moved into the local
  repository cache after successful verification. A file present in the cache
  is trusted without being verified again, so the cache never contains
  unverified or partially downloaded files.
- **`classifier`** appends the standard Maven classifier to both the download
  and libDir target filename (`<artifactId>-<version>-<classifier>.jar`), e.g.
  `sources` or `javadoc`. If `name` is not explicitly set, it then defaults to
  `<artifactId>-<classifier>` instead of just `artifactId` — this only affects
  the internal local repository cache path (so the classified artifact gets
  its own cache entry instead of colliding with the unclassified one), not the
  libDir filename, which always uses `<artifactId>-<version>[-<classifier>].jar`
  in this mode.

Without `artifactId`, everything works as before: `url` must be the full,
literal download URL, and no checksum is verified — with one exception, see
below.

### GitHub releases (`{version}` placeholder)

Without `artifactId`, `url` may contain a `{version}` placeholder that gets
substituted with `version` before download. If the url is a `github.com`
releases-download url (`https://github.com/<owner>/<repo>/releases/download/...`)
and `version` is `RELEASE` or `LATEST` (case-insensitive — GitHub has no
separate distinction between the two, so both resolve the same way), the
actual tag is resolved beforehand via GitHub's "latest release" API
(`https://api.github.com/repos/<owner>/<repo>/releases/latest`). A single
leading `v`/`V` directly followed by a digit is stripped from the resolved tag
(e.g. tag `v1.2.3` → version `1.2.3`), so it can be reused as a plain version
number in the url template.

The download is then checksum-verified the same way as in Maven artifact
mode (`.sha512` > `.sha256` > `.sha1` > `.md5`, warning only if none exist) —
this works if the release also provides a matching checksum file as its own
asset, named exactly like the jar asset plus that extension.

## Building

Sources follow the Maven standard directory layout (`src/main/java`,
`src/main/resources`, `src/test/java`).

```
ant
```

The default target downloads all needed libraries, compiles, runs the tests
and produces in `build/`:

- `antbuildhelp-<build.version>.jar` — including the embedded classes of
  `json` and `proxyautoconfig` (from `lib/`), signed if
  `~/git/codeSigning.properties` exists
- `antbuildhelp-<build.version>-sources.jar`
- `antbuildhelp-<build.version>-javadoc.jar`

`ant.jar` and `ant-launcher.jar` (`lib_ant/`) and the JUnit console launcher
(`lib_test/`) are needed at build time only and are not embedded.

`build.version` is written by the BuildAndPublish tool; a plain local `ant` run
without it falls back to a placeholder version.

### Tests

JUnit 5 tests for the ANT tasks run against a local HTTP/HTTPS server
(`com.sun.net.httpserver` from the JDK), so they need no internet access. The
self-signed certificate for the HTTPS tests is generated at test runtime with
the JDK's `keytool`. The local repository cache is redirected into a temporary
directory, the real `~/.m2/repository` is never touched. A failing test stops
the build before the jar gets signed.

## Using the task in another project's build.xml

```xml
<typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />

<getDependency name="csv"
               version="26.1.1"
               url="https://some-repository.com/index.php?download=csv.jar"
               useWpad="true"
               tlsCertificateFile="zscaler-root.cer" />

<getDependency name="swt-win"
               version="4.38-win32-win32-x86_64"
               groupId="org.eclipse.swt"
               url="https://archive.eclipse.org/eclipse/downloads/drops4/R-4.38-202512010920/swt-4.38-win32-win32-x86_64.zip"
               zipEntry="swt.jar" />
<!-- useDownloadFileName (default true) names this "swt-4.38-win32-win32-x86_64.jar" - the
     zip's own name, not "swt.jar" (the extracted entry, which would collide with the Linux
     download below since both extract an entry of that same name) -->

<!-- Maven artifact mode: groupId/artifactId/version derive the download URL
     (default base https://repo1.maven.org/maven2/) and enable checksum
     verification. -->
<getDependency url="https://repo1.maven.org/maven2" groupId="com.sun.mail" artifactId="mailapi" version="2.0.1" />
or
<getDependency url="https://repo1.maven.org/maven2" groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />
or
<getDependency url="https://some-repository.com/maven2" groupId="de.soderer" artifactId="csv" version="26.1.1" />

<!-- classifier: downloads the "sources" jar alongside the regular one -->
<getDependency groupId="de.soderer" artifactId="JavaUtilities" version="26.2.51" classifier="sources" />

<!-- GitHub releases: "{version}" placeholder in url, resolved via GitHub's API when
     version is RELEASE/LATEST; checksum verification applies here too if the release
     provides a matching .sha512/.sha256/.sha1/.md5 asset. -->
<getDependency name="somelib"
               version="RELEASE"
               url="https://github.com/someowner/somelib/releases/download/v{version}/somelib-{version}.jar" />

<!-- useDownloadFileName defaults to true (shown here explicitly): names the libDir file as
     the download itself specifies (here via Content-Disposition, since the query-string url
     gives no filename of its own) instead of "csv-26.1.1.jar". Set to "false" to opt out. -->
<getDependency name="csv"
               version="26.1.1"
               url="https://some-repository.com/index.php?download=csv.jar"
               useDownloadFileName="true" />
```

## Using `getMavenDependency` (Maven-only shorthand)

For pure Maven artifacts, `getMavenDependency` is shorter and clearer than
`getDependency` — no plain-URL-related attributes, `artifactId` is required
(fails fast if forgotten, rather than silently downloading a literal `url`
as-is), and `url` is renamed to `repositoryUrl` since it can never mean a
literal download url here:

```xml
<getMavenDependency groupId="org.apache.poi" artifactId="poi" version="5.2.4" />

<getMavenDependency groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />

<!-- private Maven-layout mirror as repository base, instead of Maven Central -->
<getMavenDependency repositoryUrl="https://some-repository.com/maven2" groupId="de.soderer"
                    artifactId="soderer-utilities" version="26.2.53" />
<getMavenDependency repositoryUrl="https://some-repository.com/maven2" groupId="de.soderer"
                    artifactId="soderer-utilities" version="26.2.53" classifier="sources" />
```

Everything documented above under "Maven artifact mode" for `getDependency`
(checksum verification, `RELEASE`/`LATEST` resolution, `classifier`,
`groupId` defaulting to `de.soderer`, etc.) applies identically here — this
is the same `DependencyResolver` underneath, just without the attributes
that only make sense for plain-URL downloads (`zipEntry`,
`useDownloadFileName`, the `{version}` placeholder).

## Using `resolveDependencies`

Registered in `antlib.xml` like the other tasks, so the same `typedef` makes it
available:

```xml
<typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />

<resolveDependencies repositoryRoot="${user.home}/.m2/repository"
                     groupId="de.soderer"
                     versionsJsonUrl="https://www.soderer.de/index.php?download=Versions.json"
                     username="myuser"
                     password="mypassword"
                     useWpad="true"
                     tlsCertificateFile="zscaler-root.cer">
    <dependency name="RestClient" version="latest" />
    <dependency name="SomeThirdPartyLib" version="1.2.3"
                url="https://example.com/libs/{name}-{version}.jar"
                tlsCertificateFile="example-com-root.cer" />
</resolveDependencies>
```

The task attributes `proxyUrl`, `pacUrl`, `useWpad` and `tlsCertificateFile`
apply to the Versions.json request and to all downloads. A `<dependency>` may
override `tlsCertificateFile` for its own download. The former attribute
`tlsCertCheck`, which switched off certificate validation entirely, no longer
exists.

## Using the CLI

```
java -jar antbuildhelp.jar --name=mailapi --version=2.0.1 --groupId=com.sun.mail --libDir=lib \
    --url=https://repo1.maven.org/maven2/com/sun/mail/mailapi/2.0.1/mailapi-2.0.1.jar
```

Equivalent, using Maven artifact mode instead (derives the URL, verifies the
checksum, `name` defaults to `mailapi`):

```
java -jar antbuildhelp.jar --groupId=com.sun.mail --artifactId=mailapi --version=2.0.1 --libDir=lib
```

Or resolving the latest released version automatically:

```
java -jar antbuildhelp.jar --groupId=com.sun.mail --artifactId=mailapi --version=RELEASE --libDir=lib
```

GitHub releases, using the `{version}` placeholder and resolving the latest tag:

```
java -jar antbuildhelp.jar --name=somelib --version=RELEASE --libDir=lib \
    --url=https://github.com/someowner/somelib/releases/download/v{version}/somelib-{version}.jar
```

Downloading the `sources` classifier jar for a Maven artifact:

```
java -jar antbuildhelp.jar --groupId=de.soderer --artifactId=JavaUtilities \
    --version=26.2.51 --classifier=sources --libDir=lib
```

Naming the file as the download/server specifies:

```
java -jar antbuildhelp.jar --name=csv --version=26.1.1 --libDir=lib \
    --url=https://some-repository.com/index.php?download=csv.jar --useDownloadFileName
```

Run `--help` for the full option list, including Maven artifact mode details.

## Known open points

- No dependencies file (YAML/JSON/XML) support yet — one `<getDependency>` call
  (or one CLI invocation) per library.
- A `Content-Disposition`-derived file name is not remembered in the local
  repository cache (see `useDownloadFileName` above), so a later project
  getting the same dependency from the cache may name the file differently.
