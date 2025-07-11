/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.base.project;

import java.io.*;
import java.net.MalformedURLException;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.Iterator;

import ghidra.app.plugin.core.analysis.AutoAnalysisManager;
import ghidra.app.util.importer.ProgramLoader;
import ghidra.app.util.opinion.*;
import ghidra.framework.Application;
import ghidra.framework.client.*;
import ghidra.framework.cmd.Command;
import ghidra.framework.model.*;
import ghidra.framework.options.Options;
import ghidra.framework.project.DefaultProjectManager;
import ghidra.framework.store.FileSystem;
import ghidra.framework.store.FolderItem;
import ghidra.framework.store.LockException;
import ghidra.program.model.lang.*;
import ghidra.program.model.listing.Program;
import ghidra.program.util.DefaultLanguageService;
import ghidra.test.ProjectTestUtils;
import ghidra.util.*;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;

/**
 * Helper class for using Ghidra in a "batch" mode. This class provides methods
 * for importing, opening, saving, and analyzing program.
 * <p>
 * <b>Note: </b>Before using this class you must initialize the Ghidra system.  See
 * {@link Application#initializeApplication} for more information.
 */
public class GhidraProject {

	private static final TaskMonitor MONITOR = TaskMonitor.DUMMY;

	private GhidraProjectManager projectManager = new GhidraProjectManager();

	private boolean deleteOnClose = false;
	private Project project;
	private ProjectData projectData;
	private HashMap<Program, Integer> openPrograms = new HashMap<>();// program->transaction

	/**
	 * Returns an instance of an open Ghidra Project that can be used to
	 * open/save programs.
	 *
	 * @param projectsDir the directory containing the Ghidra project.
	 * @param projectName the name of the ghidra project.
	 * @return an open ghidra project.
	 * @throws NotFoundException if the file for the project was
	 * not found.
	 * @throws NotOwnerException if the project owner is not the user
	 * @throws LockException if the project is already opened by another user
	 * @throws IOException if an IO-related problem occurred
	 */
	public static GhidraProject openProject(String projectsDir, String projectName)
			throws NotFoundException, NotOwnerException, LockException, IOException {
		return new GhidraProject(projectsDir, projectName, false);
	}

	/**
	 * Returns an instance of an open Ghidra Project that can be used to
	 * open/save programs.
	 *
	 * @param projectsDir the directory containing the Ghidra project.
	 * @param projectName the name of the ghidra project.
	 * @param restoreProject if true the project tool state is restored
	 * @return an open ghidra project.
	 * @throws NotFoundException if the file for the project was not found.
	 * @throws NotOwnerException if the project owner is not the user
	 * @throws LockException if the project is already opened by another user
	 * @throws IOException if an IO-related problem occurred
	 */
	public static GhidraProject openProject(String projectsDir, String projectName,
			boolean restoreProject)
			throws NotFoundException, NotOwnerException, LockException, IOException {
		return new GhidraProject(projectsDir, projectName, restoreProject);
	}

	private GhidraProject(String projectParentDir, String projectName, boolean restoreProject)
			throws NotFoundException, NotOwnerException, LockException, IOException {
		if (!ghidra.framework.Application.isInitialized()) {
			throw new AssertException("The GhidraProject requires the system to be " +
				"initialized before usage.  See GhidraApplication.initialize() for more " +
				"information.");
		}

		ProjectLocator projectLocator = new ProjectLocator(projectParentDir, projectName);
		project = projectManager.openProject(projectLocator, restoreProject, false);
		projectData = project.getProjectData();
	}

	/**
	 * Creates a new non-shared Ghidra project to be used for storing programs.
	 * 
	 * <P><B>Note:  Calling this method will delete any existing project files on disk that 
	 * match the given project name. 
	 * </B>
	 *
	 * @param projectDirPath the directory path to contain the new Ghidra project.
	 * @param projectName the name of the project to be created.
	 * @param temporary if true, deletes the project when it is closed - useful for testing.
	 * @return an open ghidra project.
	 * @throws IOException if there was a problem accessing the project
	 */
	public static GhidraProject createProject(String projectDirPath, String projectName,
			boolean temporary) throws IOException {

		// perform cleanup so previous tests do not interfere with each other
		deletePreviousProject(projectDirPath, projectName);

		return new GhidraProject(projectDirPath, projectName, null, temporary);
	}

	/**
	 * Get/Create shared repository.
	 * 
	 * @param host Ghidra Server host
	 * @param port Ghidra Server port (0 = use default port)
	 * @param repositoryName The repository name
	 * @param createIfNeeded if true repository will be created if it does not exist
	 * @throws DuplicateNameException if the repository name already exists
	 * @return A {@link RepositoryAdapter handle} to the new repository
	 */
	public static RepositoryAdapter getServerRepository(String host, int port,
			String repositoryName, boolean createIfNeeded) throws DuplicateNameException {

		RepositoryServerAdapter repositoryServer = ClientUtil.getRepositoryServer(host, port, true);
		if (!repositoryServer.isConnected()) {
			return null;
		}
		try {
			boolean exists = false;
			for (String name : repositoryServer.getRepositoryNames()) {
				if (name.equals(repositoryName)) {
					exists = true;
					break;
				}
			}
			if (exists) {
				return repositoryServer.getRepository(repositoryName);
			}
			return repositoryServer.createRepository(repositoryName);
		}
		catch (NotConnectedException e) {
			Msg.error(GhidraProject.class, "Unexpected exception getting server repository", e);
		}
		catch (IOException e) {
			ClientUtil.handleException(null, e, "Get Repository", false, null);
		}
		return null;
	}

	private GhidraProject(Project project) {

		if (!ghidra.framework.Application.isInitialized()) {
			throw new AssertException("The GhidraProject requires the system to be " +
				"initialized before usage.  See GhidraApplication.initialize() for more " +
				"information.");
		}

		this.project = project;
		this.projectData = project.getProjectData();
	}

	private GhidraProject(String projectParentDir, String projectName, RepositoryAdapter repository,
			boolean temporary) throws IOException {

		if (!Application.isInitialized()) {
			throw new AssertException("The GhidraProject requires the system to be " +
				"initialized before usage.  See GhidraApplication.initialize() for more " +
				"information.");
		}

		ProjectLocator projectLocator = new ProjectLocator(projectParentDir, projectName);
		try {
			deleteOnClose = temporary;
			project = projectManager.createProject(projectLocator, null, !temporary);
			if (project == null) {
				throw new IOException("Failed to create project: " + projectName);
			}
			projectData = project.getProjectData();
		}
		catch (OverlappingFileLockException e) {
			throw new IOException("Unable to lock project: " + projectLocator);
		}
		catch (MalformedURLException e) {
			throw new IOException("Bad Project URL: " + projectLocator);
		}
	}

	/**
	 * Returns the project manager
	 * @return the project manager
	 */
	public DefaultProjectManager getProjectManager() {
		return projectManager;
	}

	/**
	 * {@return the underlying Project instance or null if project was opened for
	 * READ access only.}
	 */
	public Project getProject() {
		return project;
	}

	/**
	 * {@return the underlying ProjectData instance.}
	 */
	public ProjectData getProjectData() {
		return projectData;
	}

	/**
	 * Closes the ghidra project, closing (without saving!) any open programs in
	 * that project. Also deletes the project if created as a temporary project.
	 */
	public void close() {
		Iterator<Program> it = openPrograms.keySet().iterator();
		while (it.hasNext()) {
			Program p = it.next();
			int id = (openPrograms.get(p)).intValue();
			if (id >= 0) {
				p.endTransaction(id, true);
			}
			p.release(this);
		}

		openPrograms.clear();
		if (project != null) {
			project.close();
			project = null;
		}

		if (deleteOnClose) {
			ProjectLocator projectLocator = projectData.getProjectLocator();
			if (projectLocator.getMarkerFile().exists()) {
				projectManager.deleteProject(projectLocator);
			}
		}
	}

	/**
	 * Updates the flag passed to this project at construction time.
	 *
	 * @param toDelete true to delete on close; false in the opposite condition
	 */
	public void setDeleteOnClose(boolean toDelete) {
		deleteOnClose = toDelete;
	}

	/**
	 * Closes the given program. Any changes in the program will be lost.
	 *
	 * @param program the program to close.
	 */
	public void close(Program program) {
		Integer id = openPrograms.remove(program);
		if (id != null) {
			if (id.intValue() >= 0) {
				program.endTransaction(id.intValue(), true);
			}
			program.release(this);
		}
	}

	/**
	 * Opens a program.
	 *
	 * @param folderPath
	 *            the path of the program within the project. ("/" is root)
	 * @param programName
	 *            the name of the program to open.
	 * @param readOnly
	 *            flag if the program will only be read and not written.
	 * @return an open program.
	 * @throws IOException
	 *             if there was a problem accessing the program
	 */
	public Program openProgram(String folderPath, String programName, boolean readOnly)
			throws IOException {
		String programPath = folderPath + FileSystem.SEPARATOR_CHAR + programName;
		DomainFile df = projectData.getFile(programPath);
		if (df == null) {
			throw new FileNotFoundException("File not found: " + programPath);
		}
		if (Program.class.isAssignableFrom(df.getDomainObjectClass())) {
			Program p;
			try {
				if (readOnly) {
					p = (Program) df.getReadOnlyDomainObject(this, FolderItem.LATEST_VERSION,
						MONITOR);
				}
				else {
					p = (Program) df.getDomainObject(this, true, false, MONITOR);
				}
			}
			catch (VersionException e) {
				throw new IOException(e.getMessage());
			}
			catch (CancelledException e) {
				throw new IOException("Cancelled");
			}
			initializeProgram(p, readOnly);
			return p;
		}
		throw new IOException("File is not a program: " + programPath);
	}

	/*
	 * private Program createProgram(String programName, String processorName)
	 * throws IOException, LanguageNotFoundException { Language lang =
	 * getLanguage(processorName); if (lang == null) { Err.show(null, "No
	 * Language", "No language for processor "+processorName); return null; }
	 * Program p = new ProgramDB(programName,lang, this); initializeProgram(p,
	 * false); return p; }
	 */

	/**
	 * Saves any changes in the program back to its file. If the program does
	 * not have an associated file (it was created), then it is an error to call
	 * this method, use saveAs instead.
	 * Any open transaction will be terminated.
	 * @param program
	 *            the program to be saved.
	 * @throws IOException
	 *             if there was a problem accessing the program
	 */
	public void save(Program program) throws IOException {
		int id = -1;
		Integer idInt = openPrograms.get(program);
		if (idInt != null) {
			id = idInt.intValue();
		}
		if (id >= 0) {
			program.endTransaction(id, true);
		}
		try {
			program.getDomainFile().save(MONITOR);
		}
		catch (CancelledException e) {
			throw new IOException("Cancelled");
		}
		if (id >= 0) {
			openPrograms.put(program, Integer.valueOf(program.startTransaction("")));
		}
	}

	/**
	 * {@return the root folder for the Ghidra project.}
	 */
	public DomainFolder getRootFolder() {
		return projectData.getRootFolder();
	}

	/**
	 * Saves the given program to the project with the given name.
	 *
	 * @param program
	 *            the program to be saved
	 * @param folderPath
	 *            the path where to save the program.
	 * @param name
	 *            the name to save the program as.
	 * @param overWrite
	 *            if true, any existing program with that name will be
	 *            over-written.
	 * @throws DuplicateFileException
	 *             if a file exists with that name and overwrite is false or overwrite failed
	 * @throws InvalidNameException
	 *             the name is null or has invalid characters.
	 * @throws IOException
	 *             if there was a problem accessing the program
	 */
	public void saveAs(Program program, String folderPath, String name, boolean overWrite)
			throws InvalidNameException, IOException {

		if (program == null) {
			throw new IllegalArgumentException("Program is null!");
		}
		int id = -1;
		Integer intID = openPrograms.get(program);
		if (intID != null) {
			id = intID.intValue();
		}
		if (id >= 0) {
			program.endTransaction(id, true);
		}
		boolean success = false;
		try {
			DomainFolder folder = projectData.getFolder(folderPath);
			try {
				if (folder == null) {
					throw new IOException("folder not found: " + folderPath);
				}
				if (overWrite) {
					DomainFile df = folder.getFile(name);
					if (df != null) {
						df.delete();
					}
				}
				folder.createFile(name, program, MONITOR);
				success = true;
			}
			catch (DuplicateFileException e) {
				if (overWrite) {
					throw new IOException("Failed to overwrite existing project file: " + name);
				}
				throw e;
			}
		}
		catch (CancelledException e1) {
			throw new IOException("Cancelled");
		}
		finally {
			if (success || id >= 0) {
				openPrograms.put(program, Integer.valueOf(program.startTransaction("")));
			}
		}

	}

	/**
	 * Saves the given program to as a packed file.
	 *
	 * @param program
	 *            the program to be saved
	 * @param file
	 *            the packed file destination.
	 * @param overWrite
	 *            if true, any existing program with that name will be
	 *            over-written.
	 * @throws InvalidNameException
	 *             the name is null or has invalid characters.
	 * @throws IOException
	 *             if there was a problem accessing the program
	 */
	public void saveAsPackedFile(Program program, File file, boolean overWrite)
			throws InvalidNameException, IOException {

		if (program == null) {
			throw new IllegalArgumentException("Program is null!");
		}
		int id = -1;
		Integer intID = openPrograms.get(program);
		if (intID != null) {
			id = intID.intValue();
		}
		if (id >= 0) {
			program.endTransaction(id, true);
		}
		boolean success = false;
		try {
			if (file.exists()) {
				if (overWrite) {
					if (!file.delete()) {
						throw new IOException("Failed to overwrite existing project file: " + file);
					}
				}
				else {
					throw new DuplicateFileException("File already exists: " + file);
				}
			}
			program.saveToPackedFile(file, TaskMonitor.DUMMY);
		}
		catch (CancelledException e1) {
			throw new IOException("Cancelled");
		}
		finally {
			if (success || id >= 0) {
				openPrograms.put(program, Integer.valueOf(program.startTransaction("")));
			}
		}
	}

	/**
	 * Creates a checkpoint in the program. Any changes since the last
	 * checkpoint can be instantly undone by calling the rollback command.
	 *
	 * @param program
	 *            the program to be checkpointed.
	 */
	public void checkPoint(Program program) {
		Integer id = openPrograms.get(program);
		if (id == null || id.intValue() < 0) {
			throw new IllegalStateException("Cannot checkpoint a read-only program");
		}
		program.endTransaction(id.intValue(), true);
		openPrograms.put(program, Integer.valueOf(program.startTransaction("")));
	}

	/**
	 * Rolls back any changes to the program since the last checkpoint.
	 *
	 * @param program
	 *            the program to be rolled back.
	 */
	public void rollback(Program program) {
		Integer id = openPrograms.get(program);
		if (id == null || id.intValue() < 0) {
			throw new IllegalStateException("Cannot rollback a read-only program");
		}
		program.endTransaction(id.intValue(), false);
		openPrograms.put(program, Integer.valueOf(program.startTransaction("")));
	}

	/**
	 * Invokes the auto-analyzer on the program. Depending on which analyzers
	 * are in the classpath, generally will disassemble at entry points, and
	 * create and analyze functions that are called.
	 *
	 * @param program the program to analyze.
	 */
	public static void analyze(Program program) {
		AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
		mgr.initializeOptions();
		mgr.reAnalyzeAll(null);
		mgr.startAnalysis(MONITOR);
	}

	/**
	 * Debug version of the auto_analyzer. Same as regular analyzer except that
	 * any stack traces are not trapped.
	 *
	 * @param program
	 *            the program to be analyzed
	 * @param debug
	 *            true to allow stack traces to propagate out.
	 */
	public void analyze(Program program, boolean debug) {
		AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
		mgr.setDebug(debug);
		analyze(program);
	}

	/**
	 * {@return a PropertList containing all the analysis option properties that
	 * can be set. Changing the value of the analysis properties will affect
	 * what happens when the analyze call is made.}
	 *
	 * @param program
	 *            the program whose analysis options are to be set.
	 */
	public Options getAnalysisOptions(Program program) {
		return program.getOptions(Program.ANALYSIS_PROPERTIES);
	}

	/**
	 * Executes the give command on the program.
	 *
	 * @param cmd
	 *            the command to be applied to the program.
	 * @param program
	 *            the program on which the command is to be applied.
	 */
	public void execute(Command<Program> cmd, Program program) {
		AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
		cmd.applyTo(program);
		mgr.initializeOptions();
		mgr.startAnalysis(MONITOR);
	}

	private void initializeProgram(Program program, boolean readOnly) {
		if (program == null) {
			return;
		}
		int id = -1;
		if (!readOnly) {
			id = program.startTransaction("Batch Processing");
			AutoAnalysisManager mgr = AutoAnalysisManager.getAnalysisManager(program);
			mgr.initializeOptions();
		}
		openPrograms.put(program, id);
	}

	/**
	 * Automatically imports the given {@link File} with the best matching {@link Loader} that
	 * supports the given language and compiler specification.
	 * <p>
	 * NOTE: It is the responsibility of the caller to release the returned {@link Program} 
	 * with {@link Program#release(Object)} when it is no longer needed, with {@code this} 
	 * {@link GhidraProject} instance as the consumer.
	 * 
	 * @param file The {@link File} to import
	 * @param language The desired {@link Language}
	 * @param compilerSpec The desired {@link CompilerSpec compiler specification}
	 * @return The imported {@link Program}
	 * @throws IOException if there was an IO-related problem loading
	 * @throws LanguageNotFoundException if there was a problem getting the language		
	 * @throws CancelledException if the operation was cancelled 
	 * @throws VersionException if there was an issue with database versions, probably due to a 
	 *   failed language upgrade
	 * @throws LoadException if there was a problem loading
	 * @deprecated Use {@link ProgramLoader}
	 */
	@Deprecated(since = "12.0", forRemoval = true)
	public Program importProgram(File file, Language language, CompilerSpec compilerSpec)
			throws CancelledException, VersionException, LanguageNotFoundException, LoadException,
			IOException {
		try (LoadResults<Program> loadResults = ProgramLoader.builder()
				.source(file)
				.project(project)
				.language(language)
				.compiler(compilerSpec)
				.monitor(MONITOR)
				.load()) {
			Program program = loadResults.getPrimaryDomainObject(this);
			initializeProgram(program, false);
			return program;
		}
	}

	/**
	 * Automatically imports the given {@link File} with the best matching {@link Loader} that
	 * supports the given processor.
	 * <p>
	 * NOTE: It is the responsibility of the caller to release the returned {@link Program} 
	 * with {@link Program#release(Object)} when it is no longer needed, with {@code this} 
	 * {@link GhidraProject} instance as the consumer.
	 * 
	 * @param file The {@link File} to import
	 * @param processor The desired {@link Processor}
	 * @return The imported {@link Program}
	 * @throws IOException if there was an IO-related problem loading
	 * @throws LanguageNotFoundException if there was a problem getting the language		
	 * @throws CancelledException if the operation was cancelled 
	 * @throws VersionException if there was an issue with database versions, probably due to a 
	 *   failed language upgrade
	 * @throws LoadException if there was a problem loading
	 * @deprecated Use {@link ProgramLoader}
	 */
	@Deprecated(since = "12.0", forRemoval = true)
	public Program importProgram(File file, Processor processor) throws CancelledException,
			VersionException, LanguageNotFoundException, LoadException, IOException {
		LanguageService svc = DefaultLanguageService.getLanguageService();
		Language language = svc.getDefaultLanguage(processor);
		CompilerSpec compilerSpec = language.getDefaultCompilerSpec();
		return importProgram(file, language, compilerSpec);
	}

	/**
	 * Automatically imports the given {@link File} with the given {@link Loader}.
	 * <p>
	 * NOTE: It is the responsibility of the caller to release the returned {@link Program} 
	 * with {@link Program#release(Object)} when it is no longer needed, with {@code this} 
	 * {@link GhidraProject} instance as the consumer.
	 * 
	 * @param file The {@link File} to import
	 * @param loaderClass The desired {@link Loader}
	 * @return The imported {@link Program}
	 * @throws IOException if there was an IO-related problem loading
	 * @throws LanguageNotFoundException if there was a problem getting the language		
	 * @throws CancelledException if the operation was cancelled 
	 * @throws VersionException if there was an issue with database versions, probably due to a 
	 *   failed language upgrade
	 * @throws LoadException if there was a problem loading
	 * @deprecated Use {@link ProgramLoader}
	 */
	@Deprecated(since = "12.0", forRemoval = true)
	public Program importProgram(File file, Class<? extends Loader> loaderClass)
			throws CancelledException, VersionException, LanguageNotFoundException, LoadException,
			IOException {
		try (LoadResults<Program> loadResults = ProgramLoader.builder()
				.source(file)
				.project(project)
				.loaders(loaderClass)
				.monitor(MONITOR)
				.load()) {
			Program program = loadResults.getPrimaryDomainObject(this);
			initializeProgram(program, false);
			return program;
		}
	}

	/**
	 * Automatically imports the given {@link File} with the given {@link Loader}, {@link Language},
	 * and {@link CompilerSpec compiler specification}.
	 * <p>
	 * NOTE: It is the responsibility of the caller to release the returned {@link Program} 
	 * with {@link Program#release(Object)} when it is no longer needed, with {@code this} 
	 * {@link GhidraProject} instance as the consumer.
	 * 
	 * @param file The {@link File} to import
	 * @param loaderClass The desired {@link Loader}
	 * @param language The desired {@link Language}
	 * @param compilerSpec The desired {@link CompilerSpec compiler specification}
	 * @return The imported {@link Program}
	 * @throws IOException if there was an IO-related problem loading
	 * @throws LanguageNotFoundException if there was a problem getting the language		
	 * @throws CancelledException if the operation was cancelled 
	 * @throws VersionException if there was an issue with database versions, probably due to a 
	 *   failed language upgrade
	 * @throws LoadException if there was a problem loading
	 * @deprecated Use {@link ProgramLoader}
	 */
	@Deprecated(since = "12.0", forRemoval = true)
	public Program importProgram(File file, Class<? extends Loader> loaderClass, Language language,
			CompilerSpec compilerSpec) throws CancelledException, VersionException,
			LanguageNotFoundException, LoadException, IOException {
		try (LoadResults<Program> loadResults = ProgramLoader.builder()
				.source(file)
				.project(project)
				.loaders(loaderClass)
				.language(language)
				.compiler(compilerSpec)
				.monitor(MONITOR)
				.load()) {
			Program program = loadResults.getPrimaryDomainObject(this);
			initializeProgram(program, false);
			return program;
		}
	}

	/**
	 * Automatically imports the given {@link File}.
	 * <p>
	 * NOTE: It is the responsibility of the caller to release the returned {@link Program} 
	 * with {@link Program#release(Object)} when it is no longer needed, with {@code this} 
	 * {@link GhidraProject} instance as the consumer.
	 * 
	 * @param file The {@link File} to import
	 * @return The imported {@link Program}
	 * @throws IOException if there was an IO-related problem loading
	 * @throws LanguageNotFoundException if there was a problem getting the language		
	 * @throws CancelledException if the operation was cancelled 
	 * @throws VersionException if there was an issue with database versions, probably due to a 
	 *   failed language upgrade
	 * @throws LoadException if there was a problem loading
	 * @deprecated Use {@link ProgramLoader}
	 */
	@Deprecated(since = "12.0", forRemoval = true)
	public Program importProgram(File file) throws CancelledException, VersionException,
			LanguageNotFoundException, LoadException, IOException {
		try (LoadResults<Program> loadResults = ProgramLoader.builder()
				.source(file)
				.project(project)
				.monitor(MONITOR)
				.load()) {
			Program program = loadResults.getPrimaryDomainObject(this);
			initializeProgram(program, false);
			return program;
		}
	}

	/**
	 * Automatically imports the given {@link File}.
	 * <p>
	 * NOTE: It is the responsibility of the caller to release the returned {@link Program} 
	 * with {@link Program#release(Object)} when it is no longer needed, with {@code this} 
	 * {@link GhidraProject} instance as the consumer.
	 * 
	 * @param file The {@link File} to import
	 * @return The imported {@link Program}
	 * @throws IOException if there was an IO-related problem loading
	 * @throws LanguageNotFoundException if there was a problem getting the language		
	 * @throws CancelledException if the operation was cancelled 
	 * @throws VersionException if there was an issue with database versions, probably due to a 
	 *   failed language upgrade
	 * @throws LoadException if there was a problem loading
	 * @deprecated Use {@link ProgramLoader}
	 */
	@Deprecated(since = "12.0", forRemoval = true)
	public Program importProgramFast(File file) throws CancelledException, VersionException,
			LanguageNotFoundException, LoadException, IOException {
		return importProgram(file);
	}

//==================================================================================================
// Private Methods
//==================================================================================================

	private static void deletePreviousProject(String projectDirectoryPath, String projectName) {
		ProjectLocator url = new ProjectLocator(projectDirectoryPath, projectName);
		File projectDir = url.getProjectDir();
		if (!projectDir.exists()) {
			return;
		}

		// something didn't cleanup correctly; we need to try to cleanup
		if (!ProjectTestUtils.deleteProject(projectDirectoryPath, projectName)) {
			throw new IllegalStateException("Unable to delete test project");
		}
	}

//==================================================================================================
// Inner Classes
//==================================================================================================

	private static class GhidraProjectManager extends DefaultProjectManager {
		// exists only to call the protected constructor
	}
}
