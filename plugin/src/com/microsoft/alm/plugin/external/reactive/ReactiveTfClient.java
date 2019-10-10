package com.microsoft.alm.plugin.external.reactive;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileCopyEvent;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileListener;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileMoveEvent;
import com.intellij.openapi.vfs.VirtualFilePropertyEvent;
import com.jetbrains.rd.framework.impl.RdSecureString;
import com.jetbrains.rd.util.lifetime.Lifetime;
import com.jetbrains.rd.util.threading.SingleThreadScheduler;
import com.microsoft.alm.plugin.authentication.AuthenticationInfo;
import com.microsoft.alm.plugin.external.models.PendingChange;
import com.microsoft.alm.plugin.external.models.ServerStatusType;
import com.microsoft.alm.plugin.external.utils.ProcessHelper;
import com.microsoft.tfs.connector.ReactiveClientConnection;
import com.microsoft.tfs.model.connector.TfsCollection;
import com.microsoft.tfs.model.connector.TfsCollectionDefinition;
import com.microsoft.tfs.model.connector.TfsCredentials;
import com.microsoft.tfs.model.connector.TfsLocalPath;
import com.microsoft.tfs.model.connector.VersionNumber;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.microsoft.alm.plugin.external.reactive.Lifetimes.defineNestedLifetime;
import static com.microsoft.alm.plugin.external.reactive.Lifetimes.toDisposable;

/**
 * A model for the reactive TF client.
 */
public class ReactiveTfClient {
    private static final String REACTIVE_CLIENT_LOG_LEVEL = "INFO";

    private static final Logger ourLogger = Logger.getInstance(ReactiveTfClient.class);

    private final ReactiveClientConnection myConnection;

    public ReactiveTfClient(ReactiveClientConnection connection) {
        myConnection = connection;
    }

    public static ReactiveTfClient create(Project project, String clientPath) throws ExecutionException {
        SingleThreadScheduler scheduler = new SingleThreadScheduler(defineNestedLifetime(project), "ReactiveTfClient Scheduler");
        ReactiveClientConnection connection = new ReactiveClientConnection(scheduler);
        try {
            Path logDirectory = Paths.get(PathManager.getLogPath(), "ReactiveTfsClient");
            Path clientHomeDir = Paths.get(clientPath).getParent().getParent();
            GeneralCommandLine commandLine = ProcessHelper.patchPathEnvironmentVariable(
                    new GeneralCommandLine(
                            clientPath,
                            Integer.toString(connection.getPort()),
                            logDirectory.toString(),
                            REACTIVE_CLIENT_LOG_LEVEL)
                            .withWorkDirectory(clientHomeDir.toString()));
            ProcessHandler processHandler = new OSProcessHandler(commandLine);
            connection.getLifetime().onTerminationIfAlive(processHandler::destroyProcess);

            processHandler.addProcessListener(createProcessListener(connection));
            processHandler.startNotify();

            return new ReactiveTfClient(connection);
        } catch (Throwable t) {
            connection.terminate();
            throw t;
        }
    }

    public CompletableFuture<Void> startAsync() {
        return myConnection.startAsync().thenAccept(unused -> {
            initializeStartedConnection();
        });
    }

    public CompletableFuture<Boolean> checkVersionAsync() {
        return myConnection.getVersionAsync().thenApply(this::checkVersion);
    }

    public CompletableFuture<String> healthCheckAsync() {
        return myConnection.healthCheckAsync();
    }

    public CompletableFuture<List<PendingChange>> getPendingChangesAsync(
            URI serverUri,
            AuthenticationInfo authenticationInfo,
            Stream<Path> localPaths) {
        List<TfsLocalPath> paths = localPaths.map(path -> new TfsLocalPath(path.toString()))
                .collect(Collectors.toList());
        return getReadyCollectionAsync(serverUri, authenticationInfo)
                .thenCompose(collection -> myConnection.getPendingChangesAsync(collection, paths))
                .thenApply(changes -> changes.stream().map(pc -> new PendingChange(
                        pc.getServerItem(),
                        pc.getLocalItem(),
                        Integer.toString(pc.getVersion()),
                        pc.getOwner(),
                        pc.getDate(),
                        pc.getLock(),
                        pc.getChangeTypes().stream().map(ServerStatusType::from).collect(Collectors.toList()),
                        pc.getWorkspace(),
                        pc.getComputer(),
                        pc.isCandidate(),
                        pc.getSourceItem())).collect(Collectors.toList()));
    }

    private static ProcessListener createProcessListener(ReactiveClientConnection connection) {
        return new ProcessAdapter() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                ourLogger.info("Process is terminated, terminating the connection");
                connection.terminate();
            }
        };
    }

    private void initializeStartedConnection() {
        myConnection.getModel().getCollections().view(myConnection.getLifetime(), (lifetime, def, collection) -> {
            addFileSystemListener(lifetime, collection);
            return Unit.INSTANCE;
        });
    }

    private boolean checkVersion(VersionNumber version) {
        // For now, any version is enough.
        return true;
    }

    private CompletableFuture<TfsCollection> getReadyCollectionAsync(
            @NotNull URI serverUri,
            @NotNull AuthenticationInfo authenticationInfo) {
        TfsCredentials tfsCredentials = new TfsCredentials(
                authenticationInfo.getUserName(),
                new RdSecureString(authenticationInfo.getPassword()));
        TfsCollectionDefinition workspaceDefinition = new TfsCollectionDefinition(serverUri, tfsCredentials);

        return myConnection.getOrCreateCollectionAsync(workspaceDefinition)
                .thenCompose(workspace -> myConnection.waitForReadyAsync(workspace)
                        .thenApply(unused -> workspace));
    }

    private void notifyFileChange(VirtualFile file, TfsCollection collection) {
        String filePathString = file.getPath();
        Path filePath = Paths.get(filePathString);

        List<TfsLocalPath> mappedPaths = collection.getMappedPaths().getValueOrNull();
        if (mappedPaths == null) return;

        if (mappedPaths.stream().anyMatch(p -> filePath.startsWith(p.getPath())))
            myConnection.invalidatePathAsync(collection, new TfsLocalPath(filePathString)).exceptionally(ex -> {
                ourLogger.error(ex);
                return null;
            });
    }

    private void addFileSystemListener(Lifetime lifetime, TfsCollection workspace) {
        VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileListener() {
            @Override
            public void propertyChanged(@NotNull VirtualFilePropertyEvent event) {
                notifyFileChange(event.getFile(), workspace);
            }

            @Override
            public void contentsChanged(@NotNull VirtualFileEvent event) {
                notifyFileChange(event.getFile(), workspace);
            }

            @Override
            public void fileCreated(@NotNull VirtualFileEvent event) {
                notifyFileChange(event.getFile(), workspace);
            }

            @Override
            public void fileDeleted(@NotNull VirtualFileEvent event) {
                notifyFileChange(event.getFile(), workspace);
            }

            @Override
            public void fileMoved(@NotNull VirtualFileMoveEvent event) {
                notifyFileChange(event.getOldParent(), workspace);
                notifyFileChange(event.getNewParent(), workspace);
            }

            @Override
            public void fileCopied(@NotNull VirtualFileCopyEvent event) {
                notifyFileChange(event.getFile(), workspace);
            }
        }, toDisposable(lifetime));
    }
}
