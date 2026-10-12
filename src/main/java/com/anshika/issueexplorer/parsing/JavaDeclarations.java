package com.anshika.issueexplorer.parsing;

import java.util.List;

/** Immutable declarations extracted from one successfully parsed source file. */
public record JavaDeclarations(String packageName, List<ImportInfo> imports,
                               List<TypeInfo> types, List<MethodInfo> methods) {
    public JavaDeclarations {
        imports = List.copyOf(imports);
        types = List.copyOf(types);
        methods = List.copyOf(methods);
    }

    public record ImportInfo(String name, boolean isStatic, boolean wildcard) { }
    public record TypeInfo(String name, String kind) { }
    public record MethodInfo(String name, String signature, int startLine, int endLine, String javadoc, String comments, String body) { }
}

