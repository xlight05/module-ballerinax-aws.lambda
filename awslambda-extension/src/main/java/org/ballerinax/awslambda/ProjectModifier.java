package org.ballerinax.awslambda;

import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Module;
import io.ballerina.projects.Project;

/**
 * Test.
 *
 * @since 2.0.0
 */
public class ProjectModifier {
    private Project project;

    public ProjectModifier(Project project) {
        this.project = project;
    }

    public void addMainFuncToDocs() {
        Module defaultModule = project.currentPackage().getDefaultModule();
        for (DocumentId documentId : defaultModule.documentIds()) {
            Document document = defaultModule.document(documentId);
            Node node = document.syntaxTree().rootNode();
            MainVisitor mainVisitor = new MainVisitor();
            Node apply = node.apply(mainVisitor);
            String s = apply.toSourceCode();
            document.modify().withContent(s).apply();
        }
    }
}
