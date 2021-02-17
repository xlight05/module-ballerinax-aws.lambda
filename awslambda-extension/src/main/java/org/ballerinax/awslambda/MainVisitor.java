package org.ballerinax.awslambda;

import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.IdentifierToken;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.NodeFactory;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TreeModifier;

import java.io.PrintStream;
/**
 * Test.
 *
 * @since 2.0.0
 */
public class MainVisitor extends TreeModifier {

    public static final PrintStream OUT = System.out;
//    private List<FunctionDefinitionNode> lambdaFunctions = new ArrayList<>();

    @Override
    public FunctionDefinitionNode transform(FunctionDefinitionNode functionDefinitionNode) {
        if (functionDefinitionNode.metadata().isEmpty()) {
            return functionDefinitionNode;
        }
        MetadataNode metadataNode = functionDefinitionNode.metadata().get();
        for (AnnotationNode annotation : metadataNode.annotations()) {
            if (annotation.annotReference().kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                continue;
            }
            QualifiedNameReferenceNode qualifiedNameReferenceNode =
                    (QualifiedNameReferenceNode) annotation.annotReference();
            String modulePrefix = qualifiedNameReferenceNode.modulePrefix().text();
            String identifier = qualifiedNameReferenceNode.identifier().text();
            if (modulePrefix.equals("awslambda") && identifier.equals("Function")) {
                //MODIFY
                functionDefinitionNode.functionName();
                IdentifierToken testMethod = NodeFactory.createIdentifierToken("testMethod");
                return functionDefinitionNode.modify().withFunctionName(testMethod).apply();
//                lambdaFunctions.add(functionDefinitionNode);
//                OUT.println(functionBodyNode);
            }
        }
        return functionDefinitionNode;
    }
}
