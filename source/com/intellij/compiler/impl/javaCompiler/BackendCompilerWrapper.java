/*
 * @author: Eugene Zhuravlev
 * Date: Jan 24, 2003
 * Time: 4:25:47 PM
 */
package com.intellij.compiler.impl.javaCompiler;

import com.intellij.compiler.*;
import com.intellij.compiler.impl.CompilerUtil;
import com.intellij.compiler.make.Cache;
import com.intellij.compiler.make.CacheCorruptedException;
import com.intellij.compiler.make.MakeUtil;
import com.intellij.compiler.make.SourceFileFinder;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.compiler.Compiler;
import com.intellij.openapi.compiler.ex.CompileContextEx;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.rt.compiler.JavacRunner;
import com.intellij.util.cls.ClsFormatException;

import java.io.*;
import java.util.*;


class BackendCompilerWrapper {
  private static final Logger LOG = Logger.getInstance("#com.intellij.compiler.impl.javaCompiler.BackendCompilerWrapper");

  private final BackendCompiler myCompiler;
  private final Map<String, Set<Pair<String, String>>> myFileNameToSourceMap;
  private final List<File> myFilesToRefresh;
  private final Set<VirtualFile> mySuccesfullyCompiledJavaFiles; // VirtualFile
  private final List<TranslatingCompiler.OutputItem> myOutputItems;

  private final CompileContextEx myCompileContext;
  private final VirtualFile[] myFilesToCompile;
  private final Project myProject;
  private Set<VirtualFile> myFilesToRecompile = Collections.EMPTY_SET;
  private ClassParsingThread myClassParsingThread;
  private int myExitCode;
  private Map<Module, VirtualFile> myModuleToTempDirMap = new HashMap<Module, VirtualFile>();
  private final ProjectFileIndex myProjectFileIndex;
  private static final String PACKAGE_ANNOTATION_FILE_NAME = "package-info.java";

  public BackendCompilerWrapper(final Project project, VirtualFile[] filesToCompile, CompileContextEx compileContext, BackendCompiler compiler) {
    myProject = project;
    myCompiler = compiler;
    myCompileContext = compileContext;
    myFilesToCompile = filesToCompile;
    myProjectFileIndex = ProjectRootManager.getInstance(myProject).getFileIndex();
    mySuccesfullyCompiledJavaFiles = new HashSet<VirtualFile>(filesToCompile.length);
    myOutputItems = new ArrayList<TranslatingCompiler.OutputItem>(filesToCompile.length);
    myFileNameToSourceMap = new HashMap<String, Set<Pair<String,String>>>(filesToCompile.length);
    myFilesToRefresh = new ArrayList<File>(filesToCompile.length);
  }

  public TranslatingCompiler.OutputItem[] compile() throws CompilerException, CacheCorruptedException {
    VirtualFile[] dependentFiles = null;
    Application application = ApplicationManager.getApplication();
    COMPILE: try {
      if (myFilesToCompile.length > 0) {
        if (application.isUnitTestMode()) {
          saveTestData();
        }

        final Map<Module, Set<VirtualFile>> moduleToFilesMap = buildModuleToFilesMap(myCompileContext, myFilesToCompile);
        compileModules(moduleToFilesMap);
      }

      dependentFiles = findDependentFiles();

      if (myCompileContext.getProgressIndicator().isCanceled() || myCompileContext.getMessageCount(CompilerMessageCategory.ERROR) > 0) {
        break COMPILE;
      }

      if (dependentFiles.length > 0) {
        VirtualFile[] filesInScope = getFilesInScope(dependentFiles);
        if (filesInScope.length > 0) {
          final Map<Module, Set<VirtualFile>> moduleToFilesMap = buildModuleToFilesMap(myCompileContext, filesInScope);
          compileModules(moduleToFilesMap);
        }
      }
    }
    catch (IOException e) {
      throw new CompilerException("Process not started:\n" + e.getMessage(), e);
    }
    catch (SecurityException e) {
      throw new CompilerException("Compiler not started :" + e.getMessage(), e);
    }
    catch (IllegalArgumentException e) {
      throw new CompilerException(e.getMessage(), e);
    }
    finally {
      myCompileContext.getProgressIndicator().pushState();
      myCompileContext.getProgressIndicator().setText("Deleting temp files...");
      for (Iterator<Module> it = myModuleToTempDirMap.keySet().iterator(); it.hasNext();) {
        final Module module = it.next();
        final VirtualFile file = myModuleToTempDirMap.get(module);
        if (file != null) {
          final File ioFile = application.runReadAction(new Computable<File>() {
            public File compute() {
              return new File(file.getPath());
            }
          });
          FileUtil.asyncDelete(ioFile);
        }
      }
      myModuleToTempDirMap.clear();
      myCompileContext.getProgressIndicator().setText("Updating caches...");
      if (mySuccesfullyCompiledJavaFiles.size() > 0 || (dependentFiles != null && dependentFiles.length > 0)) {
        myCompileContext.getDependencyCache().update();
      }
      myCompileContext.getProgressIndicator().popState();
    }
    myFilesToRecompile = new HashSet<VirtualFile>(Arrays.asList(myFilesToCompile));
    if (dependentFiles != null) {
      myFilesToRecompile.addAll(Arrays.asList(dependentFiles));
    }
    myFilesToRecompile.removeAll(mySuccesfullyCompiledJavaFiles);
    processPackageInfoFiles();

    return myOutputItems.toArray(new TranslatingCompiler.OutputItem[myOutputItems.size()]);
  }

  // package-info.java hack
  private void processPackageInfoFiles() {
    if (myFilesToRecompile.size() > 0) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          final List<VirtualFile> packageInfoFiles = new ArrayList<VirtualFile>(myFilesToRecompile.size());
          for (Iterator<VirtualFile> it = myFilesToRecompile.iterator(); it.hasNext();) {
            final VirtualFile file = it.next();
            if (PACKAGE_ANNOTATION_FILE_NAME.equals(file.getName())) {
              packageInfoFiles.add(file);
            }
          }
          if (packageInfoFiles.size() > 0) {
            final Set<VirtualFile> badFiles = getFilesCompiledWithErrors();
            for (Iterator it = packageInfoFiles.iterator(); it.hasNext();) {
              final VirtualFile packageInfoFile = (VirtualFile)it.next();
              if (!badFiles.contains(packageInfoFile)) {
                myOutputItems.add(new OutputItemImpl(null, null, packageInfoFile));
                myFilesToRecompile.remove(packageInfoFile);
              }
            }
          }
        }
      });
    }
  }

  private VirtualFile[] getFilesInScope(final VirtualFile[] dependentFiles) {
    final List<VirtualFile> filesInScope = new ArrayList<VirtualFile>(dependentFiles.length);
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        for (int idx = 0; idx < dependentFiles.length; idx++) {
          VirtualFile dependentFile = dependentFiles[idx];
          if (myCompileContext.getCompileScope().belongs(dependentFile.getUrl())) {
            filesInScope.add(dependentFile);
          }
        }
      }
    });
    return filesInScope.toArray(new VirtualFile[filesInScope.size()]);
  }

  private void compileModules(final Map<Module, Set<VirtualFile>> moduleToFilesMap) throws IOException {
    final List<ModuleChunk> chunks = getModuleChunks(moduleToFilesMap);

    for (Iterator<ModuleChunk> it = chunks.iterator(); it.hasNext();) {
      final ModuleChunk chunk = it.next();

      runTransformingCompilers(chunk);

      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          final Module[] modules = chunk.getModules();
          StringBuffer names = new StringBuffer();
          for (int idx = 0; idx < modules.length; idx++) {
            Module module = modules[idx];
            if (idx > 0) {
              names.append(", ");
            }
            names.append(module.getName());
          }
          myModuleName = names.toString();
        }
      });

      File fileToDelete = null;
      final List<OutputDir> pairs = new ArrayList<OutputDir>();
      if (chunk.getModuleCount() == 1) { // optimization
        final Module module = chunk.getModules()[0];
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            final String sourcesOutputDir = getOutputDir(module);
            if (shouldCompileTestsSeparately(module)) {
              if (sourcesOutputDir != null) {
                pairs.add(new OutputDir(sourcesOutputDir, ModuleChunk.SOURCES));
              }
              final String testsOutputDir = getTestsOutputDir(module);
              if (testsOutputDir == null) {
                LOG.assertTrue(false, "Tests output dir is null for module \""+module.getName()+"\"");
              }
              pairs.add(new OutputDir(testsOutputDir, ModuleChunk.TEST_SOURCES));
            }
            else { // both sources and test sources go into the same output
              if (sourcesOutputDir == null) {
                LOG.assertTrue(false, "Sources output dir is null for module \""+module.getName()+"\"");
              }
              pairs.add(new OutputDir(sourcesOutputDir, ModuleChunk.ALL_SOURCES));
            }
          }
        });
      }
      else { // chunk has several modules
        final File outputDir = FileUtil.createTempDirectory("compile", "output");
        fileToDelete = outputDir;
        pairs.add(new OutputDir(outputDir.getPath(), ModuleChunk.ALL_SOURCES));
      }

      try {
        for (Iterator<OutputDir> i = pairs.iterator(); i.hasNext();) {
          final OutputDir outputDir = i.next();
          doCompile(chunk, outputDir.getPath(), outputDir.getKind());
          if (myCompileContext.getMessageCount(CompilerMessageCategory.ERROR) > 0) {
            return;
          }
        }
      }
      finally {
        if (fileToDelete != null) {
          FileUtil.asyncDelete(fileToDelete);
        }
      }
    }
  }

  private List<ModuleChunk> getModuleChunks(final Map<Module, Set<VirtualFile>> moduleToFilesMap) {
    final List<Module> modules = new ArrayList<Module>(moduleToFilesMap.keySet());
    final List<Chunk<Module>> chunks = ApplicationManager.getApplication().runReadAction(new Computable<List<Chunk<Module>>>() {
      public List<Chunk<Module>> compute() {
        return ModuleCompilerUtil.getSortedModuleChunks(myProject, modules.toArray(new Module[modules.size()]));
      }
    });
    final List<ModuleChunk> moduleChunks = new ArrayList<ModuleChunk>(chunks.size());
    for (Iterator<Chunk<Module>> it = chunks.iterator(); it.hasNext();) {
      moduleChunks.add(new ModuleChunk(myCompileContext, it.next(), moduleToFilesMap));
    }
    return moduleChunks;
  }

  private boolean shouldCompileTestsSeparately(Module module) {
    final String moduleTestOutputDirectory = getTestsOutputDir(module);
    if (moduleTestOutputDirectory == null) {
      return false;
    }
    final String moduleOutputDirectory = getOutputDir(module);
    return !moduleTestOutputDirectory.equals(moduleOutputDirectory);
  }

  public VirtualFile[] getFilesToRecompile() {
    return myFilesToRecompile.toArray(new VirtualFile[myFilesToRecompile.size()]);
  }

  private void saveTestData() {
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        for (int idx = 0; idx < myFilesToCompile.length; idx++) {
          VirtualFile file = myFilesToCompile[idx];
          CompilerManagerImpl.addCompiledPath(file.getPath());
        }
      }
    });
  }

  private VirtualFile[] findDependentFiles() throws CacheCorruptedException {
    myCompileContext.getProgressIndicator().setText("Checking dependencies...");
    final int[] dependentClassInfos = myCompileContext.getDependencyCache().findDependentClasses(myCompileContext, myProject, mySuccesfullyCompiledJavaFiles);
    final Set<VirtualFile> dependentFiles = new HashSet<VirtualFile>();
    final CacheCorruptedException[] _ex = new CacheCorruptedException[]{null};
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        try {
          CompilerConfiguration compilerConfiguration = CompilerConfiguration.getInstance(myProject);
          SourceFileFinder sourceFileFinder = new SourceFileFinder(myProject, myCompileContext);
          final Cache cache = myCompileContext.getDependencyCache().getCache();
          for (int idx = 0; idx < dependentClassInfos.length; idx++) {
            final int infoQName = dependentClassInfos[idx];
            final String qualifiedName = myCompileContext.getDependencyCache().resolve(infoQName);
            final VirtualFile file = sourceFileFinder.findSourceFile(qualifiedName, cache.getSourceFileName(cache.getClassId(infoQName)));
            if (file != null) {
              if (!compilerConfiguration.isExcludedFromCompilation(file)) {
                dependentFiles.add(file);
                if (ApplicationManager.getApplication().isUnitTestMode()) {
                  CompilerManagerImpl.addRecompiledPath(file.getPath());
                }
              }
            }
            else {
              if (LOG.isDebugEnabled()) {
                LOG.debug("No source file for " + myCompileContext.getDependencyCache().resolve(infoQName) + " found");
              }
            }
          }
        }
        catch (CacheCorruptedException e) {
          _ex[0] = e;
        }
      }
    });
    if (_ex[0] != null) {
      throw _ex[0];
    }
    myCompileContext.getProgressIndicator().setText("Found " + dependentFiles.size() + " dependent files");

    VirtualFile[] dependent = dependentFiles.toArray(new VirtualFile[dependentFiles.size()]);
    return dependent;
  }

  private void doCompile(final ModuleChunk chunk, String outputDir, int sourcesFilter) throws IOException {
    chunk.setSourcesFilter(sourcesFilter);

    if (ApplicationManager.getApplication().runReadAction(new Computable<Boolean>() {
      public Boolean compute() {
        return chunk.getFilesToCompile().length == 0? Boolean.TRUE : Boolean.FALSE;
      }
    }).equals(Boolean.TRUE)) {
      return; // should not invoke javac with empty sources list
    }

    final Pair<OutputParser, Process> pair;
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      pair = JavacSettings.getInstance(myProject).isTestsUseExternalCompiler()? runExternalCompiler(chunk, outputDir) : runEmbeddedJavac(chunk, outputDir);
    }
    else {
      if (System.getProperty("idea.use.embedded.javac") != null && System.getProperty("idea.use.embedded.javac").equals("true")) {
        pair = runEmbeddedJavac(chunk, outputDir);
      }
      else {
        pair = runExternalCompiler(chunk, outputDir);
      }
    }

    int exitValue = 0;
    try {
      Process process = pair.getSecond();

      final JavaCompilerParsingThread parsingThread = new JavaCompilerParsingThread(process, myCompileContext, pair.getFirst(), this);
      myClassParsingThread = new ClassParsingThread();
      myClassParsingThread.start();
      parsingThread.start();

      try {
        exitValue = process.waitFor();
      }
      catch (InterruptedException e) {
        process.destroy();
        exitValue = process.exitValue();
      }

      try {
        parsingThread.join();
      } catch (InterruptedException e) {}

      myClassParsingThread.stopParsing();

      try {
        myClassParsingThread.join();
      } catch (InterruptedException e) {}

      final Throwable error = parsingThread.getError();
      if (error != null) {
        String message = error.getMessage();
        if (error instanceof CacheCorruptedException) {
          myCompileContext.requestRebuildNextTime(message);
        }
        else {
          myCompileContext.addMessage(CompilerMessageCategory.ERROR, message, null, -1, -1);
        }
      }
    }
    finally {
      myClassParsingThread = null;
      compileFinished(exitValue, chunk, outputDir);
      myModuleName = null;
    }
  }

  private void runTransformingCompilers(final ModuleChunk chunk) {
    final Compiler[] transformers = CompilerManager.getInstance(myProject).getCompilers(JavaSourceTransformingCompiler.class);
    if (transformers.length == 0) {
      return;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Running transforming compilers...");
    }
    final Module[] modules = chunk.getModules();
    for (int idx = 0; idx < transformers.length; idx++) {
      final JavaSourceTransformingCompiler transformer = (JavaSourceTransformingCompiler)transformers[idx];
      final Map<VirtualFile, VirtualFile> originalToCopyFileMap = new HashMap<VirtualFile, VirtualFile>();
      final Application application = ApplicationManager.getApplication();
      application.invokeAndWait(new Runnable() {
        public void run() {
          for (int idx = 0; idx < modules.length; idx++) {
            final Module module = modules[idx];
            VirtualFile[] filesToCompile = chunk.getFilesToCompile(module);
            for (int j = 0; j < filesToCompile.length; j++) {
              final VirtualFile file = filesToCompile[j];
              if (transformer.isTransformable(file)) {
                application.runWriteAction(new Runnable() {
                  public void run() {
                    try {
                      VirtualFile fileCopy = createFileCopy(getTempDir(module), file);
                      originalToCopyFileMap.put(file, fileCopy);
                    }
                    catch (IOException e) {
                      // skip it
                    }
                  }
                });
              }
            }
          }
        }
      }, myCompileContext.getProgressIndicator().getModalityState());

      // do actual transform
      for (int i = 0; i < modules.length; i++) {
        final Module module = modules[i];
        final VirtualFile[] filesToCompile = chunk.getFilesToCompile(module);
        for (int j = 0; j < filesToCompile.length; j++) {
          final VirtualFile file = filesToCompile[j];
          VirtualFile fileCopy = originalToCopyFileMap.get(file);
          if (fileCopy != null) {
            final boolean ok = transformer.transform(myCompileContext, fileCopy, file);
            if (ok) {
              filesToCompile[j] = fileCopy;
            }
          }
        }
      }
    }
  }

  private VirtualFile createFileCopy(VirtualFile tempDir, final VirtualFile file) throws IOException {
    final String fileName = file.getName();
    if (tempDir.findChild(fileName) != null) {
      int idx = 0;
      while (true) {
        final String dirName = "dir" + idx++;
        final VirtualFile dir = tempDir.findChild(dirName);
        if (dir == null) {
          tempDir = tempDir.createChildDirectory(this, dirName);
          break;
        }
        if (dir.findChild(fileName) == null) {
          tempDir = dir;
          break;
        }
      }
    }
    return VfsUtil.copyFile(this, file, tempDir);
  }

  private VirtualFile getTempDir(Module module) throws IOException {
    VirtualFile tempDir = myModuleToTempDirMap.get(module);
    if (tempDir == null) {
      final String projectName = myProject.getName();
      final String moduleName = module.getName();
      File tempDirectory = FileUtil.createTempDirectory(projectName, moduleName);
      tempDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempDirectory);
      if (tempDir == null) {
        LOG.assertTrue(false, "Cannot locate temp directory " + tempDirectory.getPath() );
      }
      myModuleToTempDirMap.put(module, tempDir);
    }
    return tempDir;
  }

  private void compileFinished(int exitValue, final ModuleChunk chunk, final String outputDir) {
    if (exitValue != 0 && !myCompileContext.getProgressIndicator().isCanceled() && myCompileContext.getMessageCount(CompilerMessageCategory.ERROR) == 0) {
      myCompileContext.addMessage(
        CompilerMessageCategory.ERROR,
        "Compiler internal error. Process terminated with exit code " + exitValue, null, -1,
        -1
      );
    }
    myCompiler.processTerminated();
    final VirtualFile[] sourceRoots = chunk.getSourceRoots();
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        final Set<VirtualFile> compiledWithErrors = getFilesCompiledWithErrors();
        final FileTypeManager typeManager = FileTypeManager.getInstance();
        final String outputDirPath = outputDir.replace(File.separatorChar, '/');
        if (LOG.isDebugEnabled()) {
          LOG.debug("myFileNameToSourceMap contains entries: " + myFileNameToSourceMap.size());
        }
        for (int idx = 0; idx < sourceRoots.length; idx++) {
          final VirtualFile root = sourceRoots[idx];
          final String packagePrefix = myProjectFileIndex.getPackageNameByDirectory(root);
          if (LOG.isDebugEnabled()) {
            LOG.debug("Building output items for " + root.getPresentableUrl() + "; output dir = " + outputDirPath + "; packagePrefix = \"" + packagePrefix + "\"");
          }
          buildOutputItemsList(outputDirPath, root, typeManager, compiledWithErrors, root, packagePrefix);
        }
      }
    });
    CompilerUtil.refreshIOFiles(myFilesToRefresh.toArray(new File[myFilesToRefresh.size()]));
    myFileNameToSourceMap.clear(); // clear the map before the next use
    myFilesToRefresh.clear();
  }

  private Pair<OutputParser, Process> runExternalCompiler(final ModuleChunk chunk, final String outputDir) throws IOException, IllegalArgumentException{
    final String[] commands = myCompiler.createStartupCommand(chunk, myCompileContext, outputDir);

    final StringBuffer buf = new StringBuffer(16 * commands.length);
    for (int idx = 0; idx < commands.length; idx++) {
      String command = commands[idx];
      buf.append(command);
      buf.append(" ");
    }
    LOG.info("Running compiler: " + buf.toString());

    final Process process = Runtime.getRuntime().exec(commands);
    return new Pair<OutputParser, Process>(myCompiler.createOutputParser(), process);
  }

  private Pair<OutputParser, Process> runEmbeddedJavac(final ModuleChunk chunk, final String outputDir) throws IOException, IllegalArgumentException {
    final String[] commands = myCompiler.createStartupCommand(chunk, myCompileContext, outputDir);
    List<String> modifiedCommands = new ArrayList<String>();
    int index = commands.length;
    boolean cpKeywordDetected = false;
    for (int idx = 0; idx < commands.length; idx++) {
      String command = commands[idx];
      if ("com.sun.tools.javac.Main".equals(command)) {
        index = idx;
      }
      else {
        if (idx > index) {
          if (cpKeywordDetected) {
            cpKeywordDetected = false;
            if (command.startsWith("@")) { // expand classpath if neccesary
              command = JavacRunner.readClasspath(command.substring(1));
            }
          }
          else if ("-classpath".equals(command) || "-cp".equals(command)){
            cpKeywordDetected = true;
          }
          modifiedCommands.add(command);
        }
      }
    }

    OutputParser outputParser = myCompiler.createOutputParser();

    final PipedInputStream in = new PipedInputStream();
    PipedOutputStream out = new PipedOutputStream(in);
    final PrintWriter writer = new PrintWriter(out);

    final String[] strings = modifiedCommands.toArray(new String[modifiedCommands.size()]);

    return new Pair<OutputParser, Process>(outputParser, new Process() {
      public OutputStream getOutputStream() {
        throw new UnsupportedOperationException("Not Implemented in: " + getClass().getName());
      }

      public InputStream getInputStream() {
        throw new UnsupportedOperationException("Not Implemented in: " + getClass().getName());
      }

      public void destroy() {
        // is thrown in tests
        //throw new UnsupportedOperationException("Not Implemented in: " + getClass().getName());
      }

      public int waitFor() {
        myExitCode = com.sun.tools.javac.Main.compile(strings, writer);
        writer.println(JavaCompilerParsingThread.TERMINATION_STRING);
        writer.flush();

        return myExitCode;
      }

      public InputStream getErrorStream() {
        return in;
      }

      public int exitValue() {
        return myExitCode;
      }
    });
  }

  private Set<VirtualFile> getFilesCompiledWithErrors() {
    CompilerMessage[] messages = myCompileContext.getMessages(CompilerMessageCategory.ERROR);
    Set<VirtualFile> compiledWithErrors = Collections.EMPTY_SET;
    if (messages.length > 0) {
      compiledWithErrors = new HashSet<VirtualFile>(messages.length);
      for (int idx = 0; idx < messages.length; idx++) {
        CompilerMessage message = messages[idx];
        final VirtualFile file = message.getVirtualFile();
        if (file != null) {
          compiledWithErrors.add(file);
        }
      }
    }
    return compiledWithErrors;
  }

  private void buildOutputItemsList(final String outputDir, VirtualFile from, FileTypeManager typeManager, Set<VirtualFile> compiledWithErrors, final VirtualFile sourceRoot, final String packagePrefix) {
    final VirtualFile[] children = from.getChildren();
    for (int idx = 0; idx < children.length; idx++) {
      final VirtualFile child = children[idx];
      if (child.isDirectory()) {
        buildOutputItemsList(outputDir, child, typeManager, compiledWithErrors, sourceRoot, packagePrefix);
      }
      else {
        if (StdFileTypes.JAVA.equals(typeManager.getFileTypeByFile(child))) {
          updateOutputItemsList(outputDir, child, compiledWithErrors, sourceRoot, packagePrefix);
        }
      }
    }
  }

  protected final void processCompiledClass(String path) throws CacheCorruptedException {
    myClassParsingThread.addPath(path);
  }

  private void putName(String sourceFileName, String relativePathToSource, String pathToClass) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Registering [sourceFileName, relativePathToSource, pathToClass] = [" + sourceFileName + "; " + relativePathToSource + "; " + pathToClass + "]");
    }
    Set<Pair<String, String>> paths = myFileNameToSourceMap.get(sourceFileName);

    if (paths == null) {
      paths = new HashSet<Pair<String,String>>();
      myFileNameToSourceMap.put(sourceFileName, paths);
    }
    paths.add(new Pair<String, String>(pathToClass, relativePathToSource));
  }

  private void updateOutputItemsList(final String outputDir, VirtualFile javaFile, Set<VirtualFile> compiledWithErrors, VirtualFile sourceRoot, final String packagePrefix) {
    final Set<Pair<String, String>> paths = myFileNameToSourceMap.get(javaFile.getName());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Checking java file " + javaFile.getPresentableUrl());
      LOG.debug("myFileNameToSourceMap contains: " + paths);
    }
    if (paths != null && paths.size() > 0) {
      final String prefix = packagePrefix != null && packagePrefix.length() > 0? packagePrefix.replace('.', '/') + "/" : "";
      final String filePath = "/" + prefix + VfsUtil.getRelativePath(javaFile, sourceRoot, '/');

      for (Iterator<Pair<String, String>> it = paths.iterator(); it.hasNext();) {
        final Pair<String, String> pair = it.next();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Checking pair [pathToClass; relPathToSource] = [" + pair.getFirst() + "; " + pair.getSecond() + "]");
        }
        if (CompilerUtil.pathsEqual(filePath, pair.getSecond())) {
          final String outputPath = pair.getFirst().replace(File.separatorChar, '/');
          final Pair<String, String> realLocation = moveToRealLocation(outputDir, outputPath, javaFile);
          if (realLocation != null) {
            myOutputItems.add(new OutputItemImpl(realLocation.getFirst(), realLocation.getSecond(), javaFile));
            if (LOG.isDebugEnabled()) {
              LOG.debug("Added output item: [outputDir; outputPath; sourceFile]  = [" + realLocation.getFirst() + "; " + realLocation.getSecond() + "; " + javaFile.getPresentableUrl() + "]");
            }
            if (!compiledWithErrors.contains(javaFile)) {
              mySuccesfullyCompiledJavaFiles.add(javaFile);
            }
          }
          else {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Failed to move to real location: " + outputPath + "; from " + outputDir);
            }
          }
        }
      }
    }
  }

  private Pair<String, String> moveToRealLocation(String tempOutputDir, String pathToClass, VirtualFile sourceFile) {
    final Module module = myCompileContext.getModuleByFile(sourceFile);
    if (module == null) {
      // do not move: looks like source file has been invalidated, need recompilation
      return null;
    }
    final String realOutputDir;
    if (myProjectFileIndex.isInTestSourceContent(sourceFile)) {
      realOutputDir = getTestsOutputDir(module);
    }
    else {
      realOutputDir = getOutputDir(module);
    }

    if (CompilerUtil.pathsEqual(tempOutputDir, realOutputDir)) { // no need to move
      myFilesToRefresh.add(new File(pathToClass));
      return new Pair<String, String>(realOutputDir, pathToClass);
    }

    final String realPathToClass = realOutputDir + pathToClass.substring(tempOutputDir.length());
    final File fromFile = new File(pathToClass);
    final File toFile = new File(realPathToClass);

    boolean success = fromFile.renameTo(toFile);
    if (!success) {
      // assuming cause of the fail: intermediate dirs do not exist
      final File parentFile = toFile.getParentFile();
      if (parentFile != null) {
        parentFile.mkdirs();
        success = fromFile.renameTo(toFile); // retry after making non-existent dirs
      }
    }
    if (!success) { // failed to move the file: e.g. because source and destination reside on different mountpoints.
      try {
        FileUtil.copy(fromFile, toFile);
        FileUtil.delete(fromFile);
        success = true;
      }
      catch (IOException e) {
        LOG.info(e);
        success = false;
      }
    }
    if (success) {
      myFilesToRefresh.add(toFile);
      return new Pair<String, String>(realOutputDir, realPathToClass);
    }
    return null;
  }

  private final Map<Module, String> myModuleToTestsOutput = new HashMap<Module, String>();
  private String getTestsOutputDir(final Module module) {
    if (myModuleToTestsOutput.containsKey(module)) {
      return myModuleToTestsOutput.get(module);
    }
    final VirtualFile outputDir = myCompileContext.getModuleOutputDirectoryForTests(module);
    final String out = outputDir != null? outputDir.getPath() : null;
    myModuleToTestsOutput.put(module, out);
    return out;
  }

  private final Map<Module, String> myModuleToOutput = new HashMap<Module, String>();
  private String getOutputDir(final Module module) {
    if (myModuleToOutput.containsKey(module)) {
      return myModuleToOutput.get(module);
    }
    final VirtualFile outputDir = myCompileContext.getModuleOutputDirectory(module);
    final String out = outputDir != null? outputDir.getPath() : null;
    myModuleToOutput.put(module, out);
    return out;
  }

  private int myFilesCount = 0;
  private int myClassesCount = 0;
  private String myModuleName = null;

  public void sourceFileProcessed() {
    myFilesCount += 1;
    updateStatistics();
  }

  private void updateStatistics() {
    final StringBuffer buf = new StringBuffer(64);
    buf.append("Files: ").append(myFilesCount).append(" - Classes: ").append(myClassesCount);
    if (myModuleName != null) {
      buf.append(" - Module: ").append(myModuleName);
    }
    myCompileContext.getProgressIndicator().setText2(buf.toString());
  }

  private static Map<Module, Set<VirtualFile>> buildModuleToFilesMap(final CompileContext context, final VirtualFile[] files) {
    final Map<Module, Set<VirtualFile>> map = new com.intellij.util.containers.HashMap<Module, Set<VirtualFile>>();
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        for (int idx = 0; idx < files.length; idx++) {
          VirtualFile file = files[idx];
          final Module module = context.getModuleByFile(file);

          if (module == null) {
            continue; // looks like file invalidated
          }

          Set<VirtualFile> moduleFiles = map.get(module);
          if (moduleFiles == null) {
            moduleFiles = new HashSet<VirtualFile>();
            map.put(module, moduleFiles);
          }
          moduleFiles.add(file);
        }
      }
    });
    return map;
  }

  private class ClassParsingThread extends Thread {
    private final List<String> myPaths = new LinkedList<String>();
    private CacheCorruptedException myError = null;
    private boolean myStopped = false;

    public ClassParsingThread() {
      super("Class Parsing Thread");
    }

    public void run() {
      try {
        while (shouldProceed()) {
          for (String path = getNextPath(); path != null; path = getNextPath()) {
            processPath(path.replace('/', File.separatorChar));
          }
          try {
            Thread.sleep(5);
          }
          catch (InterruptedException ignored) {
          }
        }
      }
      catch (CacheCorruptedException e) {
        myError = e;
      }
    }

    public synchronized void addPath(String path) throws CacheCorruptedException {
      if (myError != null) {
        throw myError;
      }
      myPaths.add(path);
    }

    private synchronized String getNextPath() {
      if (myPaths.size() == 0) {
        return null;
      }
      return myPaths.remove(0);
    }

    public synchronized void stopParsing() {
      myStopped = true;
    }

    public synchronized boolean shouldProceed() {
      if (myError != null) {
        return false;
      }
      if (myPaths.size() > 0) {
        return true;
      }
      return !myStopped;
    }

    private void processPath(final String path) throws CacheCorruptedException {
      try {
        final File file = new File(path); // the file is assumed to exist!
        final int newClassQName = myCompileContext.getDependencyCache().reparseClassFile(file);
        final Cache newClassesCache = myCompileContext.getDependencyCache().getNewClassesCache();
        final String sourceFileName = newClassesCache.getSourceFileName(newClassesCache.getClassId(newClassQName));
        String relativePathToSource = "/" + MakeUtil.createRelativePathToSource(myCompileContext.getDependencyCache().resolve(newClassQName), sourceFileName);
        putName(sourceFileName, relativePathToSource, path);
      }
      catch (ClsFormatException e) {
        String message;
        final String m = e.getMessage();
        if (m == null || "".equals(m)) {
          message = "Bad class file format:\n" + path;
        }
        else {
          message = "Bad class file format: " + m + "\n" + path;
        }
        myCompileContext.addMessage(CompilerMessageCategory.ERROR, message, null, -1, -1);
      }
      finally {
        myClassesCount += 1;
        updateStatistics();
      }
    }
  }

  private static class OutputDir {
    private final String myPath;
    private final int myKind;

    public OutputDir(String path, int kind) {
      myPath = path;
      myKind = kind;
    }

    public String getPath() {
      return myPath;
    }

    public int getKind() {
      return myKind;
    }

    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof OutputDir)) {
        return false;
      }

      final OutputDir outputDir = (OutputDir)o;

      if (myKind != outputDir.myKind) {
        return false;
      }
      if (!myPath.equals(outputDir.myPath)) {
        return false;
      }

      return true;
    }

    public int hashCode() {
      int result;
      result = myPath.hashCode();
      result = 29 * result + myKind;
      return result;
    }
  }

}
