package com.netease.maven.filtering;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.filtering.AbstractMavenFilteringRequest;
import org.apache.maven.shared.filtering.DefaultMavenResourcesFiltering;
import org.apache.maven.shared.filtering.FilteringUtils;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFileFilterRequest;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.apache.maven.shared.filtering.PropertyUtils;
import org.apache.maven.shared.utils.PathTool;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.shared.utils.io.FileUtils.FilterWrapper;
import org.apache.maven.shared.utils.io.IOUtil;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.interpolation.InterpolationPostProcessor;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.SimpleRecursionInterceptor;
import org.codehaus.plexus.interpolation.SingleResponseValueSource;
import org.codehaus.plexus.interpolation.ValueSource;
import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

@Component(role = MavenResourcesFiltering.class, hint = "filtering-enhancer")
public class FilteringEnhancer extends DefaultMavenResourcesFiltering {

	private static final String[] EMPTY_STRING_ARRAY = {};

	private static final String[] DEFAULT_INCLUDES = { "**/**" };

	@Requirement
	private BuildContext buildContext;
	@Requirement
	private MavenFileFilter mavenFileFilter;

	/** {@inheritDoc} */
	public void filterResources(MavenResourcesExecution mavenResourcesExecution) throws MavenFilteringException {
		this.getLogger().info("");
		this.getLogger().info("======begin enhancer filtering=======");
		if (mavenResourcesExecution == null) {
			throw new MavenFilteringException("mavenResourcesExecution cannot be null");
		}

		if (mavenResourcesExecution.getResources() == null) {
			getLogger().info("No resources configured skip copying/filtering");
			return;
		}

		if (mavenResourcesExecution.getOutputDirectory() == null) {
			throw new MavenFilteringException("outputDirectory cannot be null");
		}

		if (mavenResourcesExecution.isUseDefaultFilterWrappers()) {
			handleDefaultFilterWrappers(mavenResourcesExecution);
		}
		for (Resource resource : mavenResourcesExecution.getResources()) {
			if (!resource.isFiltering()) {
				continue;
			}

			String targetPath = resource.getTargetPath();

			File resourceDirectory = new File(resource.getDirectory());

			if (!resourceDirectory.isAbsolute()) {
				resourceDirectory = new File(mavenResourcesExecution.getResourcesBaseDirectory(),
						resourceDirectory.getPath());
			}

			if (!resourceDirectory.exists()) {
				getLogger().info("skip non existing resourceDirectory " + resourceDirectory.getPath());
				continue;
			}

			// this part is required in case the user specified "../something"
			// as destination
			// see MNG-1345
			File outputDirectory = mavenResourcesExecution.getOutputDirectory();
			boolean outputExists = outputDirectory.exists();
			if (!outputExists && !outputDirectory.mkdirs()) {
				throw new MavenFilteringException("Cannot create resource output directory: " + outputDirectory);
			}

			boolean ignoreDelta = !outputExists || buildContext.hasDelta(mavenResourcesExecution.getFileFilters())
					|| buildContext.hasDelta(getRelativeOutputDirectory(mavenResourcesExecution));
			getLogger().debug("ignoreDelta " + ignoreDelta);
			Scanner scanner = buildContext.newScanner(resourceDirectory, ignoreDelta);

			setupScanner(resource, scanner, mavenResourcesExecution.isAddDefaultExcludes());

			scanner.scan();

			if (mavenResourcesExecution.isIncludeEmptyDirs()) {
				try {
					File targetDirectory = targetPath == null ? outputDirectory : new File(outputDirectory, targetPath);
					copyDirectoryLayout(resourceDirectory, targetDirectory, scanner);
				} catch (IOException e) {
					throw new MavenFilteringException("Cannot copy directory structure from "
							+ resourceDirectory.getPath() + " to " + outputDirectory.getPath());
				}
			}

			List<String> includedFiles = Arrays.asList(scanner.getIncludedFiles());

			getLogger().info("Copying enhancer " + includedFiles.size() + " resource"
					+ (includedFiles.size() > 1 ? "s" : "") + (targetPath == null ? "" : " to " + targetPath));

			for (String name : includedFiles) {
				getLogger().debug("Copying file " + name);
				File source = new File(resourceDirectory, name);

				File destinationFile = getDestinationFile(outputDirectory, targetPath, name, mavenResourcesExecution);
				boolean filteredExt = filteredFileExtension(source.getName(),
						mavenResourcesExecution.getNonFilteredFileExtensions());
				if (!filteredExt) {
					continue;
				}
				mavenFileFilter.copyFile(source, destinationFile, true, mavenResourcesExecution.getFilterWrappers(),
						mavenResourcesExecution.getEncoding(), mavenResourcesExecution.isOverwrite());
			}

			// deal with deleted source files

			scanner = buildContext.newDeleteScanner(resourceDirectory);

			setupScanner(resource, scanner, mavenResourcesExecution.isAddDefaultExcludes());

			scanner.scan();

			List<String> deletedFiles = Arrays.asList(scanner.getIncludedFiles());

			for (String name : deletedFiles) {
				File destinationFile = getDestinationFile(outputDirectory, targetPath, name, mavenResourcesExecution);

				destinationFile.delete();

				buildContext.refresh(destinationFile);
			}

		}
	}

	public class EnhencerWrappers extends FilterWrapper {
		private LinkedHashSet<String> delimiters;

		private MavenProject project;

		private ValueSource propertiesValueSource;

		private List<String> projectStartExpressions;

		private String escapeString;

		private boolean escapeWindowsPaths;

		private final MavenSession mavenSession;

		private boolean supportMultiLineFiltering;

		EnhencerWrappers(LinkedHashSet<String> delimiters, MavenProject project, MavenSession mavenSession,
				ValueSource propertiesValueSource, List<String> projectStartExpressions, String escapeString,
				boolean escapeWindowsPaths, boolean supportMultiLineFiltering) {
			super();
			this.delimiters = delimiters;
			this.project = project;
			this.mavenSession = mavenSession;
			this.propertiesValueSource = propertiesValueSource;
			this.projectStartExpressions = projectStartExpressions;
			this.escapeString = escapeString;
			this.escapeWindowsPaths = escapeWindowsPaths;
			this.supportMultiLineFiltering = supportMultiLineFiltering;
		}

		@Override
		public Reader getReader(Reader reader) {
			Interpolator interpolator = createInterpolator(delimiters, projectStartExpressions, propertiesValueSource,
					project, mavenSession, escapeString, escapeWindowsPaths);

			MultiDelimiterInterpolatorEnhencerFilterReaderLineEnding filterReader = new MultiDelimiterInterpolatorEnhencerFilterReaderLineEnding(
					reader, interpolator, supportMultiLineFiltering);

			final RecursionInterceptor ri;
			if (projectStartExpressions != null && !projectStartExpressions.isEmpty()) {
				ri = new PrefixAwareRecursionInterceptor(projectStartExpressions, true);
			} else {
				ri = new SimpleRecursionInterceptor();
			}

			filterReader.setRecursionInterceptor(ri);
			filterReader.setDelimiterSpecs(delimiters);

			filterReader.setInterpolateWithPrefixPattern(false);
			filterReader.setEscapeString(escapeString);

			return filterReader;
		}

	}

	private static Interpolator createInterpolator(LinkedHashSet<String> delimiters,
			List<String> projectStartExpressions, ValueSource propertiesValueSource, MavenProject project,
			MavenSession mavenSession, String escapeString, boolean escapeWindowsPaths) {
		FilteringEnhancerInterpolator interpolator = new FilteringEnhancerInterpolator();
		interpolator.setDelimiterSpecs(delimiters);
		interpolator.addValueSource(propertiesValueSource);
		if (project != null) {
			interpolator.addValueSource(new PrefixedObjectValueSource(projectStartExpressions, project, true));
		}
		if (mavenSession != null) {
			interpolator.addValueSource(new PrefixedObjectValueSource("session", mavenSession));

			final Settings settings = mavenSession.getSettings();
			if (settings != null) {
				interpolator.addValueSource(new PrefixedObjectValueSource("settings", settings));
				interpolator.addValueSource(
						new SingleResponseValueSource("localRepository", settings.getLocalRepository()));
			}
		}

		interpolator.setEscapeString(escapeString);

		if (escapeWindowsPaths) {
			interpolator.addPostProcessor(new InterpolationPostProcessor() {
				public Object execute(String expression, Object value) {
					if (value instanceof String) {
						return FilteringUtils.escapeWindowsPath((String) value);
					}

					return value;
				}
			});
		}
		return interpolator;
	}

	private void handleDefaultFilterWrappers(MavenResourcesExecution mavenResourcesExecution)
			throws MavenFilteringException {
		List<FileUtils.FilterWrapper> filterWrappers = new ArrayList<FileUtils.FilterWrapper>();
		if (mavenResourcesExecution.getFilterWrappers() != null) {
			filterWrappers.addAll(mavenResourcesExecution.getFilterWrappers());
		}
		filterWrappers.addAll(getDefaultFilterWrappers(mavenResourcesExecution));
		mavenResourcesExecution.setFilterWrappers(filterWrappers);
	}

	private List<FileUtils.FilterWrapper> getDefaultFilterWrappers(final AbstractMavenFilteringRequest req)
			throws MavenFilteringException {
		// backup values
		boolean supportMultiLineFiltering = req.isSupportMultiLineFiltering();

		// compensate for null parameter value.
		final AbstractMavenFilteringRequest request = req == null ? new MavenFileFilterRequest() : req;

		request.setSupportMultiLineFiltering(supportMultiLineFiltering);

		// Here we build some properties which will be used to read some
		// properties files
		// to interpolate the expression ${ } in this properties file

		// Take a copy of filterProperties to ensure that evaluated filterTokens
		// are not propagated
		// to subsequent filter files. Note: this replicates current behaviour
		// and seems to make sense.

		final Properties baseProps = new Properties();

		// Project properties
		if (request.getMavenProject() != null) {
			baseProps.putAll(request.getMavenProject().getProperties() == null ? Collections.emptyMap()
					: request.getMavenProject().getProperties());
		}
		// TODO this is NPE free but do we consider this as normal
		// or do we have to throw an MavenFilteringException with mavenSession
		// cannot be null
		//
		// khmarbaise: 2016-05-21:
		// If we throw an MavenFilteringException tests will fail which is
		// caused by for example:
		// void copyFile( File from, final File to, boolean filtering,
		// List<FileUtils.FilterWrapper> filterWrappers,
		// String encoding )
		// in MavenFileFilter interface where no MavenSession is given.
		// So changing here to throw a MavenFilteringException would make
		// it necessary to change the interface or we need to find a better
		// solution.
		//
		if (request.getMavenSession() != null) {
			// User properties have precedence over system properties
			baseProps.putAll(request.getMavenSession().getSystemProperties());
			baseProps.putAll(request.getMavenSession().getUserProperties());
		}

		// now we build properties to use for resources interpolation

		final Properties filterProperties = new Properties();

		File basedir = request.getMavenProject() != null ? request.getMavenProject().getBasedir() : new File(".");

		loadProperties(filterProperties, basedir, request.getFileFilters(), baseProps);
		if (filterProperties.size() < 1) {
			filterProperties.putAll(baseProps);
		}

		if (request.getMavenProject() != null) {
			if (request.isInjectProjectBuildFilters()) {
				List<String> buildFilters = new ArrayList<String>(request.getMavenProject().getBuild().getFilters());

				// JDK-8015656: (coll) unexpected NPE from removeAll
				if (request.getFileFilters() != null) {
					buildFilters.removeAll(request.getFileFilters());
				}

				loadProperties(filterProperties, basedir, buildFilters, baseProps);
			}

			// Project properties
			filterProperties.putAll(request.getMavenProject().getProperties() == null ? Collections.emptyMap()
					: request.getMavenProject().getProperties());
		}
		if (request.getMavenSession() != null) {
			// User properties have precedence over system properties
			filterProperties.putAll(request.getMavenSession().getSystemProperties());
			filterProperties.putAll(request.getMavenSession().getUserProperties());
		}

		if (request.getAdditionalProperties() != null) {
			// additional properties wins
			filterProperties.putAll(request.getAdditionalProperties());
		}

		List<FileUtils.FilterWrapper> defaultFilterWrappers = request == null
				? new ArrayList<FileUtils.FilterWrapper>(1)
				: new ArrayList<FileUtils.FilterWrapper>(request.getDelimiters().size() + 1);

		if (getLogger().isDebugEnabled()) {
			getLogger().debug("properties used " + filterProperties);
		}

		final ValueSource propertiesValueSource = new PropertiesBasedValueSource(filterProperties);

		if (request != null) {
			FileUtils.FilterWrapper wrapper = new EnhencerWrappers(request.getDelimiters(), request.getMavenProject(),
					request.getMavenSession(), propertiesValueSource, request.getProjectStartExpressions(),
					request.getEscapeString(), request.isEscapeWindowsPaths(), request.isSupportMultiLineFiltering());

			defaultFilterWrappers.add(wrapper);
		}

		return defaultFilterWrappers;
	}

	void loadProperties(Properties filterProperties, File basedir, List<String> propertiesFilePaths,
			Properties baseProps) throws MavenFilteringException {
		if (propertiesFilePaths != null) {
			Properties workProperties = new Properties();
			workProperties.putAll(baseProps);

			for (String filterFile : propertiesFilePaths) {
				if (StringUtils.isEmpty(filterFile)) {
					// skip empty file name
					continue;
				}
				try {
					File propFile = FileUtils.resolveFile(basedir, filterFile);
					Properties properties = PropertyUtils.loadPropertyFile(propFile, workProperties);
					filterProperties.putAll(properties);
					workProperties.putAll(properties);
				} catch (IOException e) {
					throw new MavenFilteringException("Error loading property file '" + filterFile + "'", e);
				}
			}
		}
	}

	private File getDestinationFile(File outputDirectory, String targetPath, String name,
			MavenResourcesExecution mavenResourcesExecution) throws MavenFilteringException {
		String destination = name;

		if (mavenResourcesExecution.isFilterFilenames() && mavenResourcesExecution.getFilterWrappers().size() > 0) {
			destination = filterFileName(destination, mavenResourcesExecution.getFilterWrappers());
		}

		if (targetPath != null) {
			destination = targetPath + "/" + destination;
		}

		File destinationFile = new File(destination);
		if (!destinationFile.isAbsolute()) {
			destinationFile = new File(outputDirectory, destination);
		}

		if (!destinationFile.getParentFile().exists()) {
			destinationFile.getParentFile().mkdirs();
		}
		return destinationFile;
	}

	private String filterFileName(String name, List<FilterWrapper> wrappers) throws MavenFilteringException {

		Reader reader = new StringReader(name);
		for (FilterWrapper wrapper : wrappers) {
			reader = wrapper.getReader(reader);
		}

		StringWriter writer = new StringWriter();

		try {
			IOUtil.copy(reader, writer);
		} catch (IOException e) {
			throw new MavenFilteringException("Failed filtering filename" + name, e);
		}

		String filteredFilename = writer.toString();

		if (getLogger().isDebugEnabled()) {
			getLogger().debug("renaming filename " + name + " to " + filteredFilename);
		}
		return filteredFilename;
	}

	private void copyDirectoryLayout(File sourceDirectory, File destinationDirectory, Scanner scanner)
			throws IOException {
		if (sourceDirectory == null) {
			throw new IOException("source directory can't be null.");
		}

		if (destinationDirectory == null) {
			throw new IOException("destination directory can't be null.");
		}

		if (sourceDirectory.equals(destinationDirectory)) {
			throw new IOException("source and destination are the same directory.");
		}

		if (!sourceDirectory.exists()) {
			throw new IOException("Source directory doesn't exists (" + sourceDirectory.getAbsolutePath() + ").");
		}

		List<String> includedDirectories = Arrays.asList(scanner.getIncludedDirectories());

		for (String name : includedDirectories) {
			File source = new File(sourceDirectory, name);

			if (source.equals(sourceDirectory)) {
				continue;
			}

			File destination = new File(destinationDirectory, name);
			destination.mkdirs();
		}
	}

	private String[] setupScanner(Resource resource, Scanner scanner, boolean addDefaultExcludes) {
		String[] includes = null;
		if (resource.getIncludes() != null && !resource.getIncludes().isEmpty()) {
			includes = (String[]) resource.getIncludes().toArray(EMPTY_STRING_ARRAY);
		} else {
			includes = DEFAULT_INCLUDES;
		}
		scanner.setIncludes(includes);

		String[] excludes = null;
		if (resource.getExcludes() != null && !resource.getExcludes().isEmpty()) {
			excludes = (String[]) resource.getExcludes().toArray(EMPTY_STRING_ARRAY);
			scanner.setExcludes(excludes);
		}

		if (addDefaultExcludes) {
			scanner.addDefaultExcludes();
		}
		return includes;
	}

	private String getRelativeOutputDirectory(MavenResourcesExecution execution) {
		String relOutDir = execution.getOutputDirectory().getAbsolutePath();

		if (execution.getMavenProject() != null && execution.getMavenProject().getBasedir() != null) {
			String basedir = execution.getMavenProject().getBasedir().getAbsolutePath();
			relOutDir = PathTool.getRelativeFilePath(basedir, relOutDir);
			if (relOutDir == null) {
				relOutDir = execution.getOutputDirectory().getPath();
			} else {
				relOutDir = relOutDir.replace('\\', '/');
			}
		}

		return relOutDir;
	}
}
