package com.loopperfect.buckaroo.tasks;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.loopperfect.buckaroo.*;
import com.loopperfect.buckaroo.events.ReadLockFileEvent;
import io.reactivex.Observable;
import io.reactivex.Single;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Collectors;

public final class InstallExistingTasks {

    private InstallExistingTasks() {

    }

    private static Path buckarooDirectory(final Path projectDirectory) {
        return projectDirectory.resolve("buckaroo");
    }

    private static Path dependencyFolder(final Path buckarooDirectory, final RecipeIdentifier identifier) {
        return buckarooDirectory.resolve(identifier.source.map(x -> x.name).orElse("official"))
            .resolve(identifier.organization.name)
            .resolve(identifier.recipe.name);
    }

    private static String generateBuckConfigLocal(final Path target, final Path projectDirectory, final ImmutableList<RecipeIdentifier> dependencies) {
        Preconditions.checkNotNull(target);
        Preconditions.checkNotNull(projectDirectory);
        Preconditions.checkNotNull(dependencies);
        return "# Generated by Buckaroo, do not edit! \n" +
            "# This file should not be tracked in source-control. \n" +
            "[repositories]\n" +
            dependencies.stream()
                .map(x -> {
                    final String s = CommonTasks.toFolderName(x);
                    final Path dependencyFolder = dependencyFolder(buckarooDirectory(projectDirectory), x);
                    final Path p = target.toAbsolutePath().getParent().relativize(dependencyFolder.toAbsolutePath());
                    return "  " + s + " = " + p + "\n";
                })
                .collect(Collectors.joining());
    }

    private static Observable<Event> downloadResolvedDependency(final FileSystem fs, final ResolvedDependency resolvedDependency, final Path target) {

        Preconditions.checkNotNull(fs);
        Preconditions.checkNotNull(resolvedDependency);
        Preconditions.checkNotNull(target);

        final Observable<Event> downloadSourceCode = Single.fromCallable(() -> Files.exists(target))
            .flatMapObservable(exists -> {
                if (exists) {
                    return Observable.empty();
                }
                return resolvedDependency.source.join(
                    gitCommit -> CacheTasks.cloneAndCheckoutUsingCache(gitCommit, target),
                    remoteArchive -> CacheTasks.downloadUsingCache(remoteArchive, target, StandardCopyOption.REPLACE_EXISTING));
            });

        final Path buckFilePath = fs.getPath(target.toString(), "BUCK");
        final Observable<Event> downloadBuckFile = Files.exists(buckFilePath) ?
            Observable.empty() :
            resolvedDependency.buckResource
                .map(x -> CommonTasks.downloadRemoteFile(fs, x, buckFilePath))
                .orElse(Observable.empty());

        final Path buckarooDepsFilePath = fs.getPath(target.toString(), "BUCKAROO_DEPS");
        final Observable<Event> writeBuckarooDeps = Single.fromCallable(() ->
            CommonTasks.generateBuckarooDeps(resolvedDependency.dependencies))
            .flatMap(content -> CommonTasks.writeFile(
                content,
                buckarooDepsFilePath,
                true))
            .cast(Event.class)
            .toObservable();

        return Observable.concat(
            downloadSourceCode,
            downloadBuckFile,
            writeBuckarooDeps.cast(Event.class));
    }

    private static Observable<Event> installDependencyLock(final Path projectDirectory, final DependencyLock lock) {

        Preconditions.checkNotNull(projectDirectory);
        Preconditions.checkNotNull(lock);

        final Path dependencyDirectory = dependencyFolder(buckarooDirectory(projectDirectory), lock.identifier)
            .toAbsolutePath();

        return Observable.concat(

            // Download the code and BUCK file
            downloadResolvedDependency(projectDirectory.getFileSystem(), lock.origin, dependencyDirectory),

            // Touch .buckconfig
            CommonTasks.touchFile(dependencyDirectory.resolve(".buckconfig")).toObservable(),

            // Generate .buckconfig.local
            CommonTasks.writeFile(
                generateBuckConfigLocal(
                    dependencyDirectory.resolve(".buckconfig.local"),
                    projectDirectory,
                    lock.origin.dependencies.stream()
                        .map(i -> i.identifier)
                        .collect(ImmutableList.toImmutableList())),
                dependencyDirectory.resolve(".buckconfig.local"),
                true).toObservable()
        );
    }

    public static Observable<Event> installExistingDependencies(final Path projectDirectory) {

        Preconditions.checkNotNull(projectDirectory);

        final Path lockFilePath = projectDirectory.resolve("buckaroo.lock.json").toAbsolutePath();

        return Observable.concat(

            // Do we have a lock file?
            Single.fromCallable(() -> Files.exists(lockFilePath)).flatMapObservable(hasBuckarooLockFile -> {
                if (hasBuckarooLockFile) {
                    // No need to generate one
                    return Observable.empty();
                }
                // Generate a lock file
                return ResolveTasks.resolveDependencies(projectDirectory);
            }),

            MoreSingles.chainObservable(

                // Read the lock file
                CommonTasks.readLockFile(projectDirectory.resolve("buckaroo.lock.json").toAbsolutePath())
                    .map(ReadLockFileEvent::of),

                (ReadLockFileEvent event) -> {

                    final ImmutableMap<DependencyLock, Observable<Event>> installs = event.locks.entries()
                        .stream()
                        .collect(ImmutableMap.toImmutableMap(
                            i -> i,
                            i -> installDependencyLock(projectDirectory, i)
                                .filter(x->x instanceof DownloadProgress)));

                    return Observable.concat(

                        // Install the locked dependencies
                        MoreObservables.mergeMaps(installs)
                            .map(x -> DependencyInstallationProgress.of(ImmutableMap.copyOf(x))),

                        // Generate the BUCKAROO_DEPS file
                        Single.fromCallable(() -> CommonTasks.generateBuckarooDeps(event.locks.entries()
                            .stream()
                            .map(i -> ResolvedDependencyReference.of(i.identifier, i.origin.target))
                            .collect(ImmutableList.toImmutableList())))
                            .flatMap(content -> CommonTasks.writeFile(
                                content, projectDirectory.resolve("BUCKAROO_DEPS"), true))
                            .toObservable(),

                        // Touch the .buckconfig file
                        CommonTasks.touchFile(projectDirectory.resolve(".buckconfig")).toObservable(),

                        // Generate the .buckconfig.local file
                        CommonTasks.writeFile(
                            generateBuckConfigLocal(
                                projectDirectory.resolve(".buckconfig.local"),
                                projectDirectory,
                                event.locks.entries()
                                    .stream()
                                    .map(x -> x.identifier)
                                    .collect(ImmutableList.toImmutableList())),
                            projectDirectory.resolve(".buckconfig.local"),
                            true).toObservable()
                    );
                }
            ));
    }

    public static Observable<Event> installExistingDependenciesInWorkingDirectory(final Context ctx) {
        Preconditions.checkNotNull(ctx);
        return installExistingDependencies(ctx.fs.getPath(""));
    }
}
