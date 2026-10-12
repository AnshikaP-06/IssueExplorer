package com.anshika.issueexplorer.snapshot;

import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.github.GitHubClient.GitHubException;
import com.anshika.issueexplorer.github.GitHubIssueUrlParser;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipFile;

public final class SnapshotDownloader {
    public static final long DEFAULT_MAX_DOWNLOAD_BYTES = com.anshika.issueexplorer.config.AppConfig.defaults().maxDownloadBytes();
    public record DownloadResult(Path archive, boolean cached) { }

    private final GitHubClient client;

    public SnapshotDownloader(GitHubClient client) {
        this.client = client;
    }

    public DownloadResult download(String issueUrl, String sha, Path cacheRoot, long maxBytes)
            throws GitHubException {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Maximum download size must be greater than zero.");
        }
        var issue = new GitHubIssueUrlParser().parse(issueUrl);
        if (sha == null || !sha.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("Archive download requires a full 40-character commit SHA.");
        }
        Path directory = cacheRoot.toAbsolutePath().normalize()
                .resolve(issue.owner().toLowerCase(Locale.ROOT))
                .resolve(issue.repo().toLowerCase(Locale.ROOT)).resolve(sha.toLowerCase(Locale.ROOT));
        Path archive = directory.resolve("archive.zip");
        Path temporary = null;
        try {
            if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.size(archive) > maxBytes) {
                    throw sizeExceeded(maxBytes);
                }
                if (validZip(archive)) {
                    return new DownloadResult(archive, true);
                }
            }
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "archive-", ".part");
            var response = client.openArchive(issueUrl, sha);
            try (var input = response.body(); var output = Files.newOutputStream(temporary)) {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maxBytes) {
                    throw sizeExceeded(maxBytes);
                }
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new GitHubException("Archive download was interrupted.", 1);
                    }
                    if (read > maxBytes - total) {
                        throw sizeExceeded(maxBytes);
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                if (declared >= 0 && declared != total) {
                    throw new GitHubException("Archive download was incomplete; retry the download.", 1);
                }
            }
            // Read only ZIP metadata; nothing is extracted or executed.
            if (!validZip(temporary)) {
                throw new GitHubException("Downloaded response is not a complete ZIP archive.", 1);
            }
            try {
                Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            return new DownloadResult(archive, false);
        } catch (IOException | NumberFormatException exception) {
            throw new GitHubException("Could not download or save the repository archive; check your network and cache folder.", 1);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A .part file is never treated as a completed archive.
                }
            }
        }
    }

    private boolean validZip(Path archive) {
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.size() >= 0;
        } catch (IOException exception) {
            return false;
        }
    }

    private GitHubException sizeExceeded(long maxBytes) {
        return new GitHubException("Archive exceeds the maximum download size of " + maxBytes + " bytes.", 5);
    }
}

