/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinax.awslambda;

import io.ballerina.projects.Document;
import io.ballerina.projects.DocumentId;
import io.ballerina.projects.Project;
import io.ballerina.projects.internal.model.Target;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;
import io.ballerina.tools.diagnostics.Location;
import org.ballerinalang.compiler.plugins.AbstractCompilerPlugin;
import org.ballerinalang.compiler.plugins.SupportedAnnotationPackages;
import org.ballerinalang.core.model.types.TypeTags;
import org.ballerinalang.core.util.exceptions.BallerinaException;
import org.ballerinalang.model.TreeBuilder;
import org.ballerinalang.model.elements.Flag;
import org.ballerinalang.model.elements.PackageID;
import org.ballerinalang.model.symbols.SymbolOrigin;
import org.ballerinalang.model.tree.AnnotationAttachmentNode;
import org.ballerinalang.model.tree.FunctionNode;
import org.ballerinalang.model.tree.IdentifierNode;
import org.ballerinalang.model.tree.PackageNode;
import org.ballerinalang.util.diagnostic.DiagnosticLog;
import org.wso2.ballerinalang.compiler.desugar.ASTBuilderUtil;
import org.wso2.ballerinalang.compiler.semantics.model.Scope;
import org.wso2.ballerinalang.compiler.semantics.model.SymbolTable;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BAnnotationSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BInvokableSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BVarSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.Symbols;
import org.wso2.ballerinalang.compiler.semantics.model.types.BInvokableType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BNilType;
import org.wso2.ballerinalang.compiler.semantics.model.types.BType;
import org.wso2.ballerinalang.compiler.tree.BLangAnnotationAttachment;
import org.wso2.ballerinalang.compiler.tree.BLangBlockFunctionBody;
import org.wso2.ballerinalang.compiler.tree.BLangFunction;
import org.wso2.ballerinalang.compiler.tree.BLangFunctionBody;
import org.wso2.ballerinalang.compiler.tree.BLangIdentifier;
import org.wso2.ballerinalang.compiler.tree.BLangImportPackage;
import org.wso2.ballerinalang.compiler.tree.BLangPackage;
import org.wso2.ballerinalang.compiler.tree.BLangSimpleVariable;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangExpression;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangInvocation;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangLiteral;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangSimpleVarRef;
import org.wso2.ballerinalang.compiler.tree.expressions.BLangTypedescExpr;
import org.wso2.ballerinalang.compiler.tree.statements.BLangExpressionStmt;
import org.wso2.ballerinalang.compiler.tree.statements.BLangReturn;
import org.wso2.ballerinalang.compiler.tree.types.BLangType;
import org.wso2.ballerinalang.compiler.tree.types.BLangUnionTypeNode;
import org.wso2.ballerinalang.compiler.util.CompilerContext;
import org.wso2.ballerinalang.compiler.util.Name;
import org.wso2.ballerinalang.util.Flags;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler plugin to process AWS lambda function annotations.
 */
@SupportedAnnotationPackages(value = "ballerinax/awslambda:0.0.0")
public class AWSLambdaPlugin extends AbstractCompilerPlugin {

    private static final String LAMBDA_OUTPUT_ZIP_FILENAME = "aws-ballerina-lambda-functions.zip";

    private static final String AWS_LAMBDA_PACKAGE_NAME = "awslambda";

    private static final String AWS_LAMBDA_PACKAGE_ORG = "ballerinax";

    private static final String LAMBDA_PROCESS_FUNCTION_NAME = "__process";

    private static final String LAMBDA_REG_FUNCTION_NAME = "__register";

    private static final String MAIN_FUNC_NAME = "main";

    private static final PrintStream OUT = System.out;

    private static List<String> generatedFuncs = new ArrayList<>();

    private DiagnosticLog dlog;

    private SymbolTable symTable;

    public static BLangSimpleVariable createVariable(Location pos, BType type, String name, BSymbol owner) {
        BLangSimpleVariable var = (BLangSimpleVariable) TreeBuilder.createSimpleVariableNode();
        var.pos = pos;
        var.name = ASTBuilderUtil.createIdentifier(pos, name);
        var.type = type;
        var.symbol = new BVarSymbol(0, new Name(name), type.tsymbol.pkgID, type, owner, pos,
                SymbolOrigin.VIRTUAL);
        return var;
    }

    @Override
    public void init(DiagnosticLog diagnosticLog) {
        this.dlog = diagnosticLog;
    }

    public void setCompilerContext(CompilerContext context) {
        this.symTable = SymbolTable.getInstance(context);
    }

    @Override
    public void process(PackageNode packageNode) {
        List<BLangFunction> lambdaFunctions = new ArrayList<>();
        for (FunctionNode fn : packageNode.getFunctions()) {
            BLangFunction bfn = (BLangFunction) fn;
            BLangPackage bPackage = (BLangPackage) packageNode;
            if (this.isLambdaFunction(bfn, bPackage.packageID)) {
                lambdaFunctions.add(bfn);
            }
        }
        BLangPackage myPkg = (BLangPackage) packageNode;
        if (!lambdaFunctions.isEmpty()) {
            BPackageSymbol lambdaPkgSymbol = this.extractLambdaPackageSymbol(myPkg);
            if (lambdaPkgSymbol == null) {
                // this symbol will always be there, since the import is needed to add the annotation
                throw new BallerinaException("AWS Lambda package symbol cannot be found");
            }
            BLangFunction epFunc = this.extractMainFunction(myPkg);
            if (epFunc == null) {
                // main function is not there, lets create our own one
                epFunc = this.createFunction(myPkg.pos, MAIN_FUNC_NAME, myPkg);
                packageNode.addFunction(epFunc);
            } else {
                // clear out the existing statements
                ((BLangBlockFunctionBody) epFunc.body).stmts.clear();
            }
            BLangBlockFunctionBody body = (BLangBlockFunctionBody) epFunc.body;
            for (BLangFunction lambdaFunc : lambdaFunctions) {
                this.addRegisterCall(myPkg.pos, lambdaPkgSymbol, body, lambdaFunc, myPkg);
                AWSLambdaPlugin.generatedFuncs.add(lambdaFunc.name.value);
            }
            this.addProcessCall(myPkg.pos, lambdaPkgSymbol, body);
        }
    }

    private String generateProxyFunctionName(BLangFunction targetFunc) {
        return "__func_proxy__" + targetFunc.name.value;
    }

    private BLangFunction createProxyFunction(Location pos, BLangPackage myPkg, BLangFunction targetFunc) {
        List<String> paramNames = new ArrayList<>();
        List<BType> paramTypes = new ArrayList<>();
        paramNames.add(targetFunc.requiredParams.get(0).name.value);
        paramNames.add(targetFunc.requiredParams.get(1).name.value);
        paramTypes.add(targetFunc.requiredParams.get(0).type);
        paramTypes.add(symTable.anydataType);
        BLangType retType = targetFunc.returnTypeNode;
        BLangFunction func = this.createFunction(pos, generateProxyFunctionName(targetFunc), paramNames,
                paramTypes, retType, myPkg);
        BLangSimpleVarRef arg1 = this.createVariableRef(pos, func.requiredParams.get(0).symbol);
        BLangSimpleVarRef arg2 = this.createVariableRef(pos, func.requiredParams.get(1).symbol);
        BLangInvocation inv = this.createInvocationNode(targetFunc.symbol, arg1, arg2);
        BLangReturn ret = new BLangReturn();
        ret.pos = pos;
        ret.type = retType.type;
        ret.expr = inv;
        BLangBlockFunctionBody body = (BLangBlockFunctionBody) func.body;
        body.addStatement(ret);
        return func;
    }

    private BLangFunction extractMainFunction(BLangPackage myPkg) {
        for (BLangFunction func : myPkg.getFunctions()) {
            if (MAIN_FUNC_NAME.equals(func.getName().value)) {
                return func;
            }
        }
        return null;
    }

    private BPackageSymbol extractLambdaPackageSymbol(BLangPackage myPkg) {
        for (BLangImportPackage pi : myPkg.imports) {
            if (AWS_LAMBDA_PACKAGE_ORG.equals(pi.orgName.value) && pi.pkgNameComps.size() == 1 &&
                    AWS_LAMBDA_PACKAGE_NAME.equals(pi.pkgNameComps.get(0).value)) {
                return pi.symbol;
            }
        }
        return null;
    }

    private void addRegisterCall(Location pos, BPackageSymbol lamdaPkgSymbol, BLangBlockFunctionBody blockStmt,
                                 BLangFunction targetFunc, BLangPackage myPkg) {
        BLangFunction proxyFunc = createProxyFunction(pos, myPkg, targetFunc);
        myPkg.addFunction(proxyFunc);
        List<BLangExpression> exprs = new ArrayList<>();
        exprs.add(this.createStringLiteral(pos, targetFunc.name.value));
        exprs.add(this.createVariableRef(pos, proxyFunc.symbol));
        exprs.add(this.createTypeDescExpr(pos, getEventType(targetFunc)));
        BLangInvocation inv = this.createInvocationNode(lamdaPkgSymbol, LAMBDA_REG_FUNCTION_NAME, exprs);
        BLangExpressionStmt stmt = new BLangExpressionStmt(inv);
        stmt.pos = pos;
        blockStmt.addStatement(stmt);
    }

    private BLangLiteral createStringLiteral(Location pos, String value) {
        BLangLiteral stringLit = new BLangLiteral();
        stringLit.pos = pos;
        stringLit.value = value;
        stringLit.type = symTable.stringType;
        return stringLit;
    }

    private BLangTypedescExpr createTypeDescExpr(Location pos, BType type) {
        BLangTypedescExpr typeDescExpr = new BLangTypedescExpr();
        typeDescExpr.pos = pos;
        typeDescExpr.type = symTable.typeDesc;
        typeDescExpr.resolvedType = type;
        typeDescExpr.expectedType = symTable.typeDesc;
        return typeDescExpr;
    }

    private BLangSimpleVarRef createVariableRef(Location pos, BSymbol varSymbol) {
        final BLangSimpleVarRef varRef = (BLangSimpleVarRef) TreeBuilder.createSimpleVariableReferenceNode();
        varRef.pos = pos;
        varRef.variableName = ASTBuilderUtil.createIdentifier(pos, varSymbol.name.value);
        varRef.symbol = varSymbol;
        varRef.type = varSymbol.type;
        return varRef;
    }

    private void addProcessCall(Location pos, BPackageSymbol lamdaPkgSymbol, BLangBlockFunctionBody blockStmt) {
        BLangInvocation inv = this.createInvocationNode(lamdaPkgSymbol,
                LAMBDA_PROCESS_FUNCTION_NAME, new ArrayList<>(0));
        BLangExpressionStmt stmt = new BLangExpressionStmt(inv);
        stmt.pos = pos;
        blockStmt.addStatement(stmt);
    }

    private BLangInvocation createInvocationNode(BPackageSymbol pkgSymbol, String functionName,
                                                 List<BLangExpression> args) {
        BLangInvocation invocationNode = (BLangInvocation) TreeBuilder.createInvocationNode();
        BLangIdentifier name = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        name.setLiteral(false);
        name.setValue(functionName);
        invocationNode.name = name;
        invocationNode.pkgAlias = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        invocationNode.symbol = pkgSymbol.scope.lookup(new Name(functionName)).symbol;
        invocationNode.type = new BNilType();
        invocationNode.requiredArgs = args;
        return invocationNode;
    }

    private BLangInvocation createInvocationNode(BSymbol funcSymbol, BLangExpression... args) {
        BLangInvocation invocationNode = (BLangInvocation) TreeBuilder.createInvocationNode();
        BLangIdentifier name = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        name.setLiteral(false);
        name.setValue(funcSymbol.name.value);
        invocationNode.name = name;
        invocationNode.pkgAlias = (BLangIdentifier) TreeBuilder.createIdentifierNode();
        invocationNode.symbol = funcSymbol;
        invocationNode.type = funcSymbol.getType().getReturnType();
        invocationNode.requiredArgs = Arrays.asList(args);
        return invocationNode;
    }

    private BLangFunction createFunction(Location pos, String name, BLangPackage packageNode) {
        final BLangFunction bLangFunction = (BLangFunction) TreeBuilder.createFunctionNode();
        final IdentifierNode funcName = ASTBuilderUtil.createIdentifier(pos, name);
        bLangFunction.setName(funcName);
        bLangFunction.flagSet = EnumSet.of(Flag.PUBLIC);
        bLangFunction.pos = pos;
        bLangFunction.type = new BInvokableType(new ArrayList<>(), new BNilType(), null);
        bLangFunction.body = this.createBlockStmt(pos);
        BInvokableSymbol functionSymbol = Symbols.createFunctionSymbol(Flags.asMask(bLangFunction.flagSet),
                new Name(bLangFunction.name.value), packageNode.packageID,
                bLangFunction.type, packageNode.symbol, true, pos, SymbolOrigin.VIRTUAL);
        functionSymbol.scope = new Scope(functionSymbol);
        bLangFunction.symbol = functionSymbol;
        return bLangFunction;
    }

    private BLangFunction createFunction(Location pos, String name, List<String> paramNames,
                                         List<BType> paramTypes, BLangType retType, BLangPackage packageNode) {
        final BLangFunction bLangFunction = (BLangFunction) TreeBuilder.createFunctionNode();
        final IdentifierNode funcName = ASTBuilderUtil.createIdentifier(pos, name);
        bLangFunction.setName(funcName);
        bLangFunction.flagSet = EnumSet.of(Flag.PUBLIC);
        bLangFunction.pos = pos;
        bLangFunction.type = new BInvokableType(paramTypes, retType.type, null);
        bLangFunction.body = createBlockStmt(pos);
        BInvokableSymbol functionSymbol = Symbols.createFunctionSymbol(Flags.asMask(bLangFunction.flagSet),
                new Name(bLangFunction.name.value), packageNode.packageID,
                bLangFunction.type, packageNode.symbol, true, pos, SymbolOrigin.VIRTUAL);
        functionSymbol.type = bLangFunction.type;
        functionSymbol.retType = retType.type;
        functionSymbol.scope = new Scope(functionSymbol);
        bLangFunction.symbol = functionSymbol;
        for (int i = 0; i < paramNames.size(); i++) {
            BLangSimpleVariable var = createVariable(pos, paramTypes.get(i), paramNames.get(i), bLangFunction.symbol);
            bLangFunction.addParameter(var);
            functionSymbol.params.add(var.symbol);
        }
        bLangFunction.setReturnTypeNode(retType);
        return bLangFunction;
    }

    private BLangFunctionBody createBlockStmt(Location pos) {
        final BLangFunctionBody blockNode = (BLangFunctionBody) TreeBuilder.createBlockFunctionBodyNode();
        blockNode.pos = pos;
        return blockNode;
    }

    private boolean isLambdaFunction(BLangFunction fn, PackageID packageID) {
        List<BLangAnnotationAttachment> annotations = fn.annAttachments;
        boolean hasLambdaAnnon = false;
        for (AnnotationAttachmentNode attachmentNode : annotations) {
            hasLambdaAnnon = this.hasLambaAnnotation(attachmentNode);
            if (hasLambdaAnnon) {
                break;
            }
        }
        if (hasLambdaAnnon) {
            BLangFunction bfn = fn;
            if (!this.validateLambdaFunction(bfn)) {
                dlog.logDiagnostic(DiagnosticSeverity.ERROR, packageID, fn.getPosition(),
                        "Invalid function signature for an AWS lambda function: " +
                                bfn + ", it should be 'public function (awslambda:Context, anydata) returns " +
                                "json|error'");
                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    private BType getEventType(BLangFunction node) {
        return node.requiredParams.get(1).type;
    }

    private boolean validateLambdaFunction(BLangFunction node) {

        List<BLangSimpleVariable> defaultableParams = new ArrayList<>();

        for (BLangSimpleVariable var : node.requiredParams) {
            if (var.symbol.defaultableParam) {
                defaultableParams.add(var);
            }
        }

        if (node.requiredParams.size() != 2 || defaultableParams.size() > 0 || node.restParam != null) {
            return false;
        }
        BLangType type1 = node.requiredParams.get(0).getTypeNode();
        BLangType type2 = node.requiredParams.get(1).getTypeNode();
        if (!type1.type.tsymbol.name.value.equals("Context")) {
            return false;
        }
        if (!type1.type.tsymbol.pkgID.orgName.value.equals(AWS_LAMBDA_PACKAGE_ORG) ||
                !type1.type.tsymbol.pkgID.name.value.equals(AWS_LAMBDA_PACKAGE_NAME)) {
            return false;
        }
        if (type2 == null) {
            return false;
        }
        BLangType retType = node.returnTypeNode;
        if (retType instanceof BLangUnionTypeNode) {
            BLangUnionTypeNode unionType = (BLangUnionTypeNode) retType;
            Set<Integer> typeTags = new HashSet<>();
            for (BLangType memberTypeNode : unionType.memberTypeNodes) {
                typeTags.add(memberTypeNode.type.tag);
            }
            typeTags.remove(TypeTags.JSON_TAG);
            typeTags.remove(TypeTags.ERROR_TAG);
            typeTags.remove(TypeTags.NULL_TAG);
            return typeTags.isEmpty();
        } else {
            return retType.type.tag == TypeTags.JSON_TAG || retType.type.tag == TypeTags.ERROR_TAG ||
                    retType.type.tag == TypeTags.NULL_TAG;
        }
    }

    private boolean hasLambaAnnotation(AnnotationAttachmentNode attachmentNode) {
        BAnnotationSymbol symbol = ((BLangAnnotationAttachment) attachmentNode).annotationSymbol;
        return AWS_LAMBDA_PACKAGE_ORG.equals(symbol.pkgID.orgName.value) &&
                AWS_LAMBDA_PACKAGE_NAME.equals(symbol.pkgID.name.value) && "Function".equals(symbol.name.value);
    }

    @Override
    public List<Diagnostic> codeAnalyze(Project project) {
        ProjectModifier projectModifier = new ProjectModifier(project);
        projectModifier.addMainFuncToDocs();
        DocumentId next = project.currentPackage().getDefaultModule().documentIds().iterator().next();
        Document document = project.currentPackage().getDefaultModule().document(next);
        OUT.println(document.syntaxTree().toSourceCode());
        project.currentPackage().getResolution();
        return Collections.emptyList();
    }

    @Override
    public void codeGenerated(Project project, Target target) {
        if (AWSLambdaPlugin.generatedFuncs.isEmpty()) {
            // no lambda functions, nothing else to do
            return;
        }
        DocumentId next = project.currentPackage().getDefaultModule().documentIds().iterator().next();
        Document document = project.currentPackage().getDefaultModule().document(next);
        OUT.println(document.syntaxTree().toSourceCode());
        OUT.println("\t@awslambda:Function: " + String.join(", ", AWSLambdaPlugin.generatedFuncs));
        String balxName;
        try {
            String fileName = target.getExecutablePath(project.currentPackage()).getFileName().toString();
            balxName = fileName.substring(0, fileName.lastIndexOf('.'));

            this.generateZipFile(target.getExecutablePath(project.currentPackage()));
        } catch (IOException e) {
            throw new BallerinaException("Error generating AWS lambda zip file: " + e.getMessage(), e);
        }
        OUT.println("\n\tRun the following command to deploy each Ballerina AWS Lambda function:");
        try {
            OUT.println("\taws lambda create-function --function-name $FUNCTION_NAME --zip-file fileb://"
                    + target.getExecutablePath(project.currentPackage()).getParent().toString() + File.separator
                    + LAMBDA_OUTPUT_ZIP_FILENAME + " --handler " + balxName
                    + ".$FUNCTION_NAME --runtime provided --role $LAMBDA_ROLE_ARN --layers "
                    + "arn:aws:lambda:$REGION_ID:134633749276:layer:ballerina-jre11:6 --memory-size 512 --timeout 10");
        } catch (IOException e) {
            //ignored
        }
        OUT.println("\n\tRun the following command to re-deploy an updated Ballerina AWS Lambda function:");
        OUT.println("\taws lambda update-function-code --function-name $FUNCTION_NAME --zip-file fileb://"
                + LAMBDA_OUTPUT_ZIP_FILENAME);
    }

    private void generateZipFile(Path binaryPath) throws IOException {
        Path path = binaryPath.toAbsolutePath().getParent().resolve(LAMBDA_OUTPUT_ZIP_FILENAME);
        Files.deleteIfExists(path);
        Map<String, String> env = new HashMap<>();
        env.put("create", "true");
        URI uri = URI.create("jar:file:" + path.toUri().getPath());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
            Path pathInZipfile = zipfs.getPath("/" + binaryPath.getFileName());
            Files.copy(binaryPath, pathInZipfile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
