package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.parsing.JavaDirectoryParser;
import com.anshika.issueexplorer.config.AppConfig;
import com.anshika.issueexplorer.parsing.ParsingLimitException;
import picocli.CommandLine.Option;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "parse-dir", description = "Parse local Java files and print declaration counts.",
        mixinStandardHelpOptions = true)
public class ParseDirCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<path>")
    private Path path;

    @Option(names = "--threads") private Integer threads;
    @Option(names = "--config") private Path configFile;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        try {
            var config = AppConfig.load(configFile).override(null, null, null, threads, null);
            long started = System.nanoTime();
            var result = new JavaDirectoryParser(config.parserThreads(), config.maxFiles(), config.maxExtractedBytes()).parse(path);
            spec.commandLine().getErr().printf(java.util.Locale.ROOT, "Timing parse: %.3f ms%n", (System.nanoTime() - started) / 1_000_000.0);
            var output = spec.commandLine().getOut();
            for (var file : result.files()) {
                if (file.successful()) {
                    var declarations = file.declarations();
                    output.println(file.path() + ": package="
                            + (declarations.packageName().isEmpty() ? "(default)" : declarations.packageName())
                            + ", imports=" + declarations.imports().size()
                            + ", types=" + declarations.types().size()
                            + ", methods=" + declarations.methods().size());
                } else {
                    output.println(file.path() + ": FAILED - " + file.error());
                }
            }
            output.println("Java files: " + result.files().size() + "; parsed: " + result.parsed() + "; failed: " + result.failed());
            return 0;
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        } catch (ParsingLimitException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 5;
        } catch (IOException exception) {
            spec.commandLine().getErr().println("Error: Could not read the directory; check the path and permissions.");
            return 1;
        }
    }
}

