package de.soderer.antbuildhelp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standalone CLI entry point of antbuildhelp.jar ("java -jar antbuildhelp.jar --name=... ...").
 * Exposes the same dependency-resolution logic as the {@code getDependency} Ant task
 * ({@link GetDependencyTask}), via the shared, Ant-independent {@link DependencyResolver}.
 *
 * Arguments are given as "--key=value" pairs, one per DependencyResolver attribute; boolean
 * attributes (useWpad, useDownloadFileName) may also be given bare (e.g. "--useWpad" instead of
 * "--useWpad=true").
 *
 * Example:
 * <pre>{@code
 * java -jar antbuildhelp.jar \
 *     --name=RestClient --version=26.0.82 \
 *     --url=https://www.soderer.de/index.php?download=RestClient.jar \
 *     --libDir=lib --useWpad --tlsCertificateFile=zscaler-root.cer
 *
 * // Maven artifact mode: --artifactId switches on automatic URL derivation
 * // (groupId/artifactId/version, default base https://repo1.maven.org/maven2/) and
 * // checksum verification; --version may be RELEASE/LATEST to resolve via maven-metadata.xml.
 * java -jar antbuildhelp.jar --groupId=com.sun.mail --artifactId=mailapi --version=RELEASE
 * }</pre>
 */
public class AntBuildHelpMain {

	private static final String[] ALWAYS_REQUIRED_ARGS = { "version" };

	public static void main(final String[] args) {
		if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
			printUsage();
			System.exit(args.length == 0 ? 1 : 0);
		}

		try {
			final Map<String, String> options = parseArgs(args);
			for (final String requiredArg : ALWAYS_REQUIRED_ARGS) {
				if (!options.containsKey(requiredArg)) {
					throw new IllegalArgumentException("Missing required argument: --" + requiredArg);
				}
			}
			if (!options.containsKey("url") && !options.containsKey("artifactId")) {
				throw new IllegalArgumentException(
						"Missing required argument: --url (or --artifactId for Maven artifact mode)");
			}

			final DependencyResolver resolver = new DependencyResolver()
					.withName(options.get("name"))
					.withVersion(options.get("version"))
					.withUrl(options.get("url"))
					.withArtifactId(options.get("artifactId"))
					.withClassifier(options.get("classifier"))
					.withGroupId(options.get("groupId"))
					.withLibDir(resolveLibDir(options.get("libDir")))
					.withRepositoryRoot(options.get("repositoryRoot"))
					.withProxyUrl(options.get("proxyUrl"))
					.withPacUrl(options.get("pacUrl"))
					.withUseWpad(Boolean.parseBoolean(options.getOrDefault("useWpad", "false")))
					.withTlsCertificateFile(options.get("tlsCertificateFile"))
					.withZipEntry(options.get("zipEntry"))
					.withUseDownloadFileName(Boolean.parseBoolean(options.getOrDefault("useDownloadFileName",
							String.valueOf(DependencyResolver.DEFAULT_USE_DOWNLOAD_FILE_NAME))))
					.withLogger(System.out::println);

			resolver.resolve();
		} catch (final Exception e) {
			System.err.println("AntBuildHelp: " + e.getMessage());
			System.exit(1);
		}
	}

	private static Map<String, String> parseArgs(final String[] args) {
		final Map<String, String> options = new LinkedHashMap<>();
		for (final String arg : args) {
			if (!arg.startsWith("--")) {
				throw new IllegalArgumentException("Unrecognized argument: " + arg + " (expected --key=value)");
			}
			final String withoutPrefix = arg.substring(2);
			final int equalsIndex = withoutPrefix.indexOf('=');
			if (equalsIndex < 0) {
				options.put(withoutPrefix, "true"); // bare boolean flag, e.g. "--useWpad"
			} else {
				options.put(withoutPrefix.substring(0, equalsIndex), withoutPrefix.substring(equalsIndex + 1));
			}
		}
		return options;
	}

	private static Path resolveLibDir(final String libDirOption) {
		final Path libDirPath = libDirOption != null ? Paths.get(libDirOption) : Paths.get("lib");
		return libDirPath.isAbsolute() ? libDirPath : Paths.get(System.getProperty("user.dir")).resolve(libDirPath);
	}

	private static void printUsage() {
		System.out.println("AntBuildHelp - resolve and download a single dependency jar");
		System.out.println();
		System.out.println("Usage:");
		System.out.println("  java -jar antbuildhelp.jar --version=<version> (--url=<url> | --artifactId=<artifactId>) [options]");
		System.out.println();
		System.out.println("Required:");
		System.out.println("  --version=<version>             dependency version; or RELEASE/LATEST (case-insensitive) to");
		System.out.println("                                   resolve the actual version via maven-metadata.xml - Maven");
		System.out.println("                                   artifact mode only, i.e. requires --artifactId");
		System.out.println("  --url=<url>                     default mode: the full, literal download URL, optionally");
		System.out.println("                                   containing a \"{version}\" placeholder substituted with");
		System.out.println("                                   --version. If this is a github.com releases-download URL");
		System.out.println("                                   and --version is RELEASE/LATEST, the actual tag is");
		System.out.println("                                   resolved via GitHub's API first; the download is then");
		System.out.println("                                   checksum-verified if a matching asset exists, same as");
		System.out.println("                                   in Maven artifact mode.");
		System.out.println("                                   Maven artifact mode (--artifactId given): optional -");
		System.out.println("                                   treated as the Maven-layout repository BASE url (e.g. a");
		System.out.println("                                   private mirror) instead; defaults to Maven Central");
		System.out.println("                                   (https://repo1.maven.org/maven2/) when omitted");
		System.out.println("  --artifactId=<artifactId>       switches on Maven artifact mode: the download URL is");
		System.out.println("                                   derived as <url-base>/<groupId>/<artifactId>/<version>/");
		System.out.println("                                   <artifactId>-<version>.jar, and the download is verified");
		System.out.println("                                   against the strongest available checksum file");
		System.out.println("                                   (.sha512/.sha256/.sha1/.md5). One of --url/--artifactId");
		System.out.println("                                   is required.");
		System.out.println();
		System.out.println("Optional:");
		System.out.println("  --name=<name>                   local/logical dependency name, used for the local");
		System.out.println("                                   repository cache and libDir file naming; in Maven");
		System.out.println("                                   artifact mode defaults to --artifactId if not given");
		System.out.println("  --groupId=<groupId>             default: de.soderer; must be set to the real Maven");
		System.out.println("                                   groupId when using --artifactId");
		System.out.println("  --classifier=<classifier>       Maven artifact mode only: standard Maven classifier,");
		System.out.println("                                   e.g. \"sources\" or \"javadoc\" - appended to the download");
		System.out.println("                                   filename; --name then defaults to <artifactId>-<classifier>");
		System.out.println("  --libDir=<path>                 default: ./lib (relative to current directory)");
		System.out.println("  --repositoryRoot=<path>         default: ~/.m2/repository");
		System.out.println("  --proxyUrl=<host:port>          direct proxy, e.g. http://proxy:8080");
		System.out.println("  --pacUrl=<url>                  explicit PAC script URL");
		System.out.println("  --useWpad                       auto-discover PAC via WPAD");
		System.out.println("  --tlsCertificateFile=<path>     additional trusted certificate (e.g. corporate MITM proxy)");
		System.out.println("  --zipEntry=<entryPath>           extract this entry from a downloaded zip instead of using it directly");
		System.out.println("  --useDownloadFileName            default mode only, without --zipEntry: name the libDir");
		System.out.println("                                   file as the download itself specifies (Content-Disposition");
		System.out.println("                                   header, else last URL path segment if it ends in .jar/.zip)");
		System.out.println("                                   instead of <name>-<version>.jar. Defaults to true; pass");
		System.out.println("                                   --useDownloadFileName=false to always get <name>-<version>.jar");
		System.out.println();
		System.out.println("Examples:");
		System.out.println("  java -jar antbuildhelp.jar --name=RestClient --version=26.0.82 \\");
		System.out.println("      --url=https://www.soderer.de/index.php?download=RestClient.jar \\");
		System.out.println("      --libDir=lib --useWpad --tlsCertificateFile=zscaler-root.cer");
		System.out.println();
		System.out.println("  # Maven artifact mode, Maven Central, explicit version:");
		System.out.println("  java -jar antbuildhelp.jar --groupId=com.sun.mail --artifactId=mailapi --version=2.0.1");
		System.out.println();
		System.out.println("  # Maven artifact mode, resolve the latest released version:");
		System.out.println("  java -jar antbuildhelp.jar --groupId=com.sun.mail --artifactId=mailapi --version=RELEASE");
		System.out.println();
		System.out.println("  # Maven artifact mode, private Maven-layout mirror as repository base:");
		System.out.println("  java -jar antbuildhelp.jar --url=http://soderer.de/maven2 --groupId=de.soderer \\");
		System.out.println("      --artifactId=csv --version=26.1.1");
		System.out.println();
		System.out.println("  # GitHub releases, {version} placeholder, resolving the latest release tag:");
		System.out.println("  java -jar antbuildhelp.jar --name=somelib --version=RELEASE \\");
		System.out.println("      --url=https://github.com/someowner/somelib/releases/download/v{version}/somelib-{version}.jar");
		System.out.println();
		System.out.println("  # classifier, downloading the \"sources\" jar alongside the regular one:");
		System.out.println("  java -jar antbuildhelp.jar --groupId=de.soderer --artifactId=JavaUtilities \\");
		System.out.println("      --version=26.2.51 --classifier=sources");
		System.out.println();
		System.out.println("  # useDownloadFileName defaults to true (shown here explicitly) - naming the file as the");
		System.out.println("  # download/server specifies; pass --useDownloadFileName=false to opt out:");
		System.out.println("  java -jar antbuildhelp.jar --name=csv --version=26.1.1 \\");
		System.out.println("      --url=https://www.soderer.de/index.php?download=csv.jar --useDownloadFileName");
	}
}
