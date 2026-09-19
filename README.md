# AntBuildHelp

Downloads a single dependency jar into a local Maven-layout-compatible repository
cache, then copies it into a target `lib/` directory, removing any older version
of the same dependency found there first. Usable both as an Ant task and as a
standalone CLI (`java -jar antbuildhelp.jar ...`).

## Package overview

- `DependencyResolver` — the actual resolution logic (download, cache, old-version
  cleanup, proxy/PACURL/WPAD via the embedded proxyautoconfig library, TLS
  certificate trust, zip-entry extraction). No dependency on org.apache.tools.ant.*.
- `GetDependencyTask` — thin Ant task wrapper (`de.soderer.antbuildhelp.GetDependencyTask`,
  registered as `getDependency` in `antlib.xml`) around `DependencyResolver`. Covers
  plain URL downloads (optionally zip-extracted), GitHub releases, and Maven
  artifact mode (via its own `artifactId` attribute).
- `GetMavenDependencyTask` — thin Ant task wrapper (`de.soderer.antbuildhelp.GetMavenDependencyTask`,
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
- `antlib.xml` — registers the `getDependency` and `getMavenDependency` tasks;
  import both in one line with `<typedef resource="de/soderer/antbuildhelp/antlib.xml" .../>`.
- `utilities.jarinjarloader` — JarInJar support classes (`rsrc:` URL protocol),
  used to load the embedded `lib/proxyautoconfig.jar` at runtime without
  extracting it to a temp file. `JarInJarLoader.main()` itself is not used by
  either the task or the CLI; kept here in case it's useful for building a fully
  self-contained multi-jar executable later.

## Attributes / CLI options

`version` (always required); `url` and/or `artifactId` (at least one required —
see "Maven artifact mode" below); `name` (required unless `artifactId` is set,
see below); `groupId` (default `de.soderer`), `libDir` (default
`<project dir>/lib` for the Ant task, `./lib` for the CLI), `repositoryRoot`
(default `~/.m2/repository`), `proxyUrl`, `pacUrl`, `useWpad`,
`tlsCertificateFile`, `zipEntry`, `useDownloadFileName` (optional).

Proxy resolution precedence (set at most one): `proxyUrl` > `pacUrl` > `useWpad`.

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

Set `useDownloadFileName="false"` (Ant) / `--useDownloadFileName=false` (CLI)
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

- **`name`** falls back to `artifactId` if not explicitly set. (`name` is still
  what's used for the local repository cache and `libDir` file naming — it may
  intentionally differ from `artifactId`, the actual Maven coordinate.)
- **`url`**, if given, is no longer the full download URL — it's treated as the
  Maven-layout repository *base* URL (e.g. a private mirror such as
  `http://soderer.de/maven2`). If omitted, Maven Central
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

`ant.jar` is needed at compile time only (not embedded), via `lib_ant/`.

```
ant jar
```

Produces `build/antbuildhelp-<build.version>.jar`.

## Using the task in another project's build.xml

```xml
<typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />

<getDependency name="csv"
               version="26.1.1"
               url="https://www.soderer.de/index.php?download=csv.jar"
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
<getDependency url="http://soderer.de/maven2" groupId="de.soderer" artifactId="csv" version="26.1.1" />

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
               url="https://www.soderer.de/index.php?download=csv.jar"
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
<getMavenDependency repositoryUrl="https://soderer.de/maven2" groupId="de.soderer"
                    artifactId="soderer-utilities" version="26.2.53" />
<getMavenDependency repositoryUrl="https://soderer.de/maven2" groupId="de.soderer"
                    artifactId="soderer-utilities" version="26.2.53" classifier="sources" />
```

Everything documented above under "Maven artifact mode" for `getDependency`
(checksum verification, `RELEASE`/`LATEST` resolution, `classifier`,
`groupId` defaulting to `de.soderer`, etc.) applies identically here — this
is the same `DependencyResolver` underneath, just without the attributes
that only make sense for plain-URL downloads (`zipEntry`,
`useDownloadFileName`, the `{version}` placeholder).

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
    --url=https://www.soderer.de/index.php?download=csv.jar --useDownloadFileName
```

Run `--help` for the full option list, including Maven artifact mode details.

## Known open points

- No dependencies file (YAML/JSON/XML) support yet — one `<getDependency>` call
  (or one CLI invocation) per library.
- `resolveProxyViaEmbeddedPacLibrary()` reloads the nested proxyautoconfig
  classloader on every call (no caching across multiple calls in the same run).
