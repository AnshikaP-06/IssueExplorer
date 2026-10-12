package com.anshika.issueexplorer.parsing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;

public final class JavaFileParser {
    public record FileResult(String path, JavaDeclarations declarations, String error) {
        public boolean successful() {
            return declarations != null;
        }
    }

    public FileResult parse(Path file, String relativePath) {
        var parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        try {
            var result = parser.parse(file);
            // Do not index a partial AST returned after a syntax error.
            if (!result.isSuccessful() || result.getResult().isEmpty()) {
                return new FileResult(relativePath, null, "Java syntax could not be parsed.");
            }
            var unit = result.getResult().orElseThrow();
            String packageName = unit.getPackageDeclaration().map(value -> value.getNameAsString()).orElse("");
            var imports = unit.getImports().stream()
                    .map(value -> new JavaDeclarations.ImportInfo(value.getNameAsString(), value.isStatic(), value.isAsterisk()))
                    .toList();
            var types = new ArrayList<JavaDeclarations.TypeInfo>();
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                String kind = type.isEnumDeclaration() ? "enum"
                        : type.isRecordDeclaration() ? "record"
                        : type.isAnnotationDeclaration() ? "annotation"
                        : type.asClassOrInterfaceDeclaration().isInterface() ? "interface" : "class";
                types.add(new JavaDeclarations.TypeInfo(type.getNameAsString(), kind));
            }
            var methods = new ArrayList<JavaDeclarations.MethodInfo>();
            for (var method : unit.findAll(MethodDeclaration.class)) {
                var range = method.getRange().orElseThrow();
                methods.add(new JavaDeclarations.MethodInfo(method.getNameAsString(),
                        method.getDeclarationAsString(true, true, true), range.begin.line, range.end.line,
                        method.getJavadoc().map(value -> value.toText()).orElse(""),
                        method.getAllContainedComments().stream().filter(comment -> !comment.isJavadocComment())
                                .map(comment -> comment.getContent()).collect(java.util.stream.Collectors.joining("\n")),
                        method.getBody().map(value -> value.toString()).orElse("")));
            }
            return new FileResult(relativePath, new JavaDeclarations(packageName, imports, types, methods), "");
        } catch (IOException exception) {
            return new FileResult(relativePath, null, "Could not read Java file.");
        } catch (RuntimeException exception) {
            return new FileResult(relativePath, null, "Java parser failed for this file.");
        }
    }
}

