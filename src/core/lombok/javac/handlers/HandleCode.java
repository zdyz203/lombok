/*
 * Copyright (C) 2009-2017 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.Javac.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.toGetterName;
import static lombok.javac.handlers.JavacHandlerUtil.toSetterName;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.AccessLevel;
import lombok.Coded;
import lombok.Getter;
import lombok.Setter;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.experimental.Delegate;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import lombok.javac.JavacTreeMaker.TypeTag;
import lombok.javac.handlers.JavacHandlerUtil.*;

/**
 * Handles the {@code lombok.Coded} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleCode extends JavacAnnotationHandler<Coded> {

    @Override
    public void handle(AnnotationValues<Coded> annotation, JCAnnotation ast, JavacNode annotationNode) {
        addImportOfCodeUtil(annotationNode.top());

        deleteAnnotationIfNeccessary(annotationNode, Coded.class);
        deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");
        JavacNode node = annotationNode.up();

        if (node == null)
            return;

        Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
        createGetterForFields(fields, annotationNode, true);
        createSetterForFields(fields, annotationNode, true);
    }

    private void addImportOfCodeUtil(JavacNode typeNode) {
        JCTree.JCCompilationUnit unit = (JCTree.JCCompilationUnit) typeNode.get();
        for (JCTree def : unit.defs) {
            if (def instanceof JCTree.JCImport) {
                JCTree.JCImport imp0rt = (JCTree.JCImport) def;
                if (!imp0rt.staticImport
                                && imp0rt.qualid.toString().equals("com.ctrip.flight.intl.common.utils.CodeUtil"))
                    return;
            }
        }
        JavacTreeMaker maker = typeNode.getTreeMaker();
        JCTree.JCIdent packAge = maker.Ident(typeNode.toName("com.ctrip.flight.intl.common.utils"));
        JCTree.JCFieldAccess codeUtil = maker.Select(packAge, typeNode.toName("CodeUtil"));
        unit.defs = unit.defs.append(maker.Import(codeUtil, false));
    }

    public void createSetterForFields(Collection<JavacNode> fieldNodes, JavacNode annotationNode, boolean whineIfExists) {
        AccessLevel level = getAccessLevelFromSetter(AccessLevel.PUBLIC, upToTypeNode(annotationNode));
        for (JavacNode fieldNode : fieldNodes) {
            level = getAccessLevelFromSetter(level, fieldNode);
            if (level == AccessLevel.NONE)
                continue;
            createSetterForField(level, fieldNode, annotationNode, whineIfExists);
        }
    }

    public void createSetterForField(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists) {
        if (fieldNode.getKind() != Kind.FIELD) {
            fieldNode.addError("@Coded is only supported on a field.");
            return;
        }

        if(!checkFiledType(fieldNode))
            return;

        JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();

        if ((fieldDecl.mods.flags & Flags.FINAL) != 0) {
            fieldNode.addWarning("Not generating setter for this field: Setters cannot be generated for final fields.");
            return;
        }

        addNormalSetter(level, fieldNode, sourceNode, whineIfExists, fieldDecl);
        addEncodeSetter(level, fieldNode, sourceNode, whineIfExists, fieldDecl);
    }

    private boolean checkFiledType(JavacNode fieldNode) {
        JCExpression type = ((JCVariableDecl)fieldNode.get()).vartype;
        if(!(type instanceof JCTree.JCPrimitiveTypeTree)){
            fieldNode.addError("@Coded is only supported on short int long");
            return false;
        }
        switch (((JCTree.JCPrimitiveTypeTree)type).getPrimitiveTypeKind()){
            case SHORT:
            case INT:
            case LONG:
                break;
            default:
                fieldNode.addError("@Coded is only supported on short int long");
                return false;
        }
        return true;
    }

    private void addNormalSetter(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, JCVariableDecl fieldDecl) {
        String methodName = fieldNode.getName();

        switch (methodExists(methodName, fieldNode, false, 1)) {
            case EXISTS_BY_LOMBOK:
                return;
            case EXISTS_BY_USER:
                if (whineIfExists) {
                    fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists", methodName));
                }
                return;
            default:
            case NOT_EXISTS:
        }

        long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

        JCMethodDecl createdSetter = createSetter(access, fieldNode, fieldNode.getTreeMaker(), sourceNode);
        Type fieldType = getMirrorForFieldType(fieldNode);
        Type returnType;

        if (shouldReturnThis(fieldNode)) {
            Symbol.ClassSymbol sym = ((JCTree.JCClassDecl) fieldNode.up().get()).sym;
            returnType = sym == null ? null : sym.type;
        } else {
            returnType = Javac.createVoidType(fieldNode.getSymbolTable(), CTC_VOID);
        }

        injectMethod(fieldNode.up(), createdSetter, fieldType == null ? null : List.of(fieldType), returnType);
    }

    private void addEncodeSetter(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, JCVariableDecl fieldDecl) {
        String methodName = toSetterName(fieldNode);

        switch (methodExists(methodName, fieldNode, false, 1)) {
            case EXISTS_BY_LOMBOK:
                return;
            case EXISTS_BY_USER:
                if (whineIfExists) {
                    fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists", methodName));
                }
                return;
            default:
            case NOT_EXISTS:
        }

        long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

        String setterName = toSetterName(fieldNode);
        boolean shouldReturnThis = shouldReturnThis(fieldNode);
        JCMethodDecl createdSetter = createEncodeSetter(access, false, fieldNode, fieldNode.getTreeMaker(), setterName, null, shouldReturnThis, sourceNode);
        Type fieldType = getMirrorForFieldType(fieldNode);
        Type returnType;

        if (shouldReturnThis(fieldNode)) {
            Symbol.ClassSymbol sym = ((JCTree.JCClassDecl) fieldNode.up().get()).sym;
            returnType = sym == null ? null : sym.type;
        } else {
            returnType = Javac.createVoidType(fieldNode.getSymbolTable(), CTC_VOID);
        }

        injectMethod(fieldNode.up(), createdSetter, fieldType == null ? null : List.of(fieldType), returnType);
    }

    public static JCMethodDecl createEncodeSetter(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker,
                                                  String setterName, Name booleanFieldToSet, boolean shouldReturnThis, JavacNode source) {

        if (setterName == null)
            return null;

        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();

        String encodeMethodName = null;
        JCExpression type = ((JCVariableDecl)field.get()).vartype;
        switch (((JCTree.JCPrimitiveTypeTree)type).getPrimitiveTypeKind()){
            case SHORT:
                encodeMethodName = "encodeAsShort";
                break;
            case INT:
                encodeMethodName = "encodeAsInteger";
                break;
            case LONG:
                encodeMethodName = "encodeAsLong";
                break;
            default:
        }
        JCTree.JCFieldAccess encode = treeMaker.Select(treeMaker.Ident(field.toName("CodeUtil")), field.toName(encodeMethodName));
        JCTree.JCIdent encodeParam = treeMaker.Ident(fieldDecl.name);
        JCMethodInvocation encodeMethodCall = treeMaker.Apply(List.<JCExpression>nil(), encode, List.<JCExpression>of(encodeParam));
        JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
        JCTree.JCAssign assign = treeMaker.Assign(fieldRef, encodeMethodCall);

        ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
        List<JCAnnotation> nonNulls = findAnnotations(field, NON_NULL_PATTERN);
        List<JCAnnotation> nullables = findAnnotations(field, NULLABLE_PATTERN);

        Name methodName = field.toName(setterName);
        List<JCAnnotation> annsOnParam = new ListBuffer<JCAnnotation>().toList().appendList(nonNulls).appendList(nullables);

        long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, field.getContext());
        JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(flags, annsOnParam), fieldDecl.name, treeMaker.Ident(field.toName("String")), null);

        if (nonNulls.isEmpty()) {
            statements.append(treeMaker.Exec(assign));
        } else {
            JCStatement nullCheck = generateNullCheck(treeMaker, field, source);
            if (nullCheck != null) statements.append(nullCheck);
            statements.append(treeMaker.Exec(assign));
        }

        if (booleanFieldToSet != null) {
            JCTree.JCAssign setBool = treeMaker.Assign(treeMaker.Ident(booleanFieldToSet), treeMaker.Literal(CTC_BOOLEAN, 1));
            statements.append(treeMaker.Exec(setBool));
        }

        JCExpression methodType = null;
        if (shouldReturnThis) {
            methodType = cloneSelfType(field);
        }

        if (methodType == null) {
            //WARNING: Do not use field.getSymbolTable().voidType - that field has gone through non-backwards compatible API changes within javac1.6.
            methodType = treeMaker.Type(Javac.createVoidType(field.getSymbolTable(), CTC_VOID));
            shouldReturnThis = false;
        }

        if (shouldReturnThis) {
            JCTree.JCReturn returnStatement = treeMaker.Return(treeMaker.Ident(field.toName("this")));
            statements.append(returnStatement);
        }

        JCBlock methodBody = treeMaker.Block(0, statements.toList());
        List<JCTypeParameter> methodGenericParams = List.nil();
        List<JCVariableDecl> parameters = List.of(param);
        List<JCExpression> throwsClauses = List.nil();
        JCExpression annotationMethodDefaultValue = null;

        List<JCAnnotation> annsOnMethod = new ListBuffer<JCAnnotation>().toList();
        if (isFieldDeprecated(field) || deprecate) {
            annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
        }

        return recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
                methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source.get(), field.getContext());
    }

    public static JCMethodDecl createSetter(long access, JavacNode field, JavacTreeMaker treeMaker, JavacNode source) {
        String setterName = field.getName();
        boolean returnThis = shouldReturnThis(field);
        return createSetter(access, false, field, treeMaker, setterName, null, returnThis, source);
    }

    public static JCMethodDecl createSetter(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker, String setterName, Name booleanFieldToSet, boolean shouldReturnThis, JavacNode source) {
        if (setterName == null) return null;

        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();

        JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
        JCTree.JCAssign assign = treeMaker.Assign(fieldRef, treeMaker.Ident(fieldDecl.name));

        ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
        List<JCAnnotation> nonNulls = findAnnotations(field, NON_NULL_PATTERN);
        List<JCAnnotation> nullables = findAnnotations(field, NULLABLE_PATTERN);

        Name methodName = field.toName(setterName);
        List<JCAnnotation> annsOnParam = new ListBuffer<JCAnnotation>().toList().appendList(nonNulls).appendList(nullables);

        long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, field.getContext());
        JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(flags, annsOnParam), fieldDecl.name, fieldDecl.vartype, null);

        if (nonNulls.isEmpty()) {
            statements.append(treeMaker.Exec(assign));
        } else {
            JCStatement nullCheck = generateNullCheck(treeMaker, field, source);
            if (nullCheck != null) statements.append(nullCheck);
            statements.append(treeMaker.Exec(assign));
        }

        if (booleanFieldToSet != null) {
            JCTree.JCAssign setBool = treeMaker.Assign(treeMaker.Ident(booleanFieldToSet), treeMaker.Literal(CTC_BOOLEAN, 1));
            statements.append(treeMaker.Exec(setBool));
        }

        JCExpression methodType = null;
        if (shouldReturnThis) {
            methodType = cloneSelfType(field);
        }

        if (methodType == null) {
            //WARNING: Do not use field.getSymbolTable().voidType - that field has gone through non-backwards compatible API changes within javac1.6.
            methodType = treeMaker.Type(Javac.createVoidType(field.getSymbolTable(), CTC_VOID));
            shouldReturnThis = false;
        }

        if (shouldReturnThis) {
            JCTree.JCReturn returnStatement = treeMaker.Return(treeMaker.Ident(field.toName("this")));
            statements.append(returnStatement);
        }

        JCBlock methodBody = treeMaker.Block(0, statements.toList());
        List<JCTypeParameter> methodGenericParams = List.nil();
        List<JCVariableDecl> parameters = List.of(param);
        List<JCExpression> throwsClauses = List.nil();
        JCExpression annotationMethodDefaultValue = null;

        List<JCAnnotation> annsOnMethod = new ListBuffer<JCAnnotation>().toList();
        if (isFieldDeprecated(field) || deprecate) {
            annsOnMethod = annsOnMethod.prepend(treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
        }

        return recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName, methodType,
                methodGenericParams, parameters, throwsClauses, methodBody, annotationMethodDefaultValue), source.get(), field.getContext());
    }

    public void createGetterForFields(Collection<JavacNode> fieldNodes, JavacNode annotationNode,
                    boolean whineIfExists) {
        AccessLevel level = getAccessLevelFromGetter(AccessLevel.PUBLIC, upToTypeNode(annotationNode));
        for (JavacNode fieldNode : fieldNodes) {
            level = getAccessLevelFromGetter(level, fieldNode);
            if (level == AccessLevel.NONE)
                continue;
            createGetterForField(level, fieldNode, annotationNode, whineIfExists);
        }
    }

    public void createGetterForField(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists) {
        if (fieldNode.getKind() != Kind.FIELD) {
            source.addError("@Coded is only supported on a field.");
            return;
        }

        if(!checkFiledType(fieldNode))
            return;

        String name = fieldNode.getName();

        if (name == null) {
            source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
            return;
        }

        // add normal getter
		addNormalGetter(level, fieldNode, source, whineIfExists);
		// add decode getter
		addDecodeGetter(level, fieldNode, source, whineIfExists);

    }

    private void addNormalGetter(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists) {
        JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();
        String name = fieldNode.getName();
        switch (methodExists(name, fieldNode, false, 0)) {
            case EXISTS_BY_LOMBOK:
                return;
            case EXISTS_BY_USER:
                if (whineIfExists) {
                    source.addWarning(String.format("Not generating %s(): A method with that name already exists", name));
                }
                return;
            default:
            case NOT_EXISTS:
        }

        long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

        injectMethod(fieldNode.up(), createGetter(access, fieldNode, fieldNode.getTreeMaker(), source.get()),
                        List.<Type>nil(), getMirrorForFieldType(fieldNode));
    }

	private void addDecodeGetter(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists) {

        String methodName = toGetterName(fieldNode);

        if (methodName == null) {
            source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
            return;
        }

		switch (methodExists(methodName, fieldNode, false, 0)) {
			case EXISTS_BY_LOMBOK:
				return;
			case EXISTS_BY_USER:
				if (whineIfExists) {
					source.addWarning(String.format("Not generating %s(): A method with that name already exists", methodName));
				}
				return;
			default:
			case NOT_EXISTS:
		}

        JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();
		long access = toJavacModifier(level) | (fieldDecl.mods.flags & Flags.STATIC);

		injectMethod(fieldNode.up(), createDecodeGetter(access, false, fieldNode, fieldNode.getTreeMaker(), source.get()),
				List.<Type>nil(), getMirrorForFieldType(fieldNode));
	}

	public static JCMethodDecl createDecodeGetter(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker, JCTree source) {
        Name methodName = field.toName(toGetterName(field));

        List<JCStatement> statements;
        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();

        JCTree.JCFieldAccess decode = treeMaker.Select(treeMaker.Ident(field.toName("CodeUtil")), field.toName("decode"));
        JCTree.JCIdent param = treeMaker.Ident(fieldDecl.name);
        JCMethodInvocation decodeMethodCall = treeMaker.Apply(List.<JCExpression>nil(), decode, List.<JCExpression>of(param));
        JCTree.JCReturn returnStatement = treeMaker.Return(decodeMethodCall);
        statements = List.<JCStatement>of(returnStatement);
        JCBlock methodBody = treeMaker.Block(0, statements);

        List<JCTypeParameter> methodGenericParams = List.nil();
        List<JCVariableDecl> parameters = List.nil();
        List<JCExpression> throwsClauses = List.nil();
        JCExpression annotationMethodDefaultValue = null;

        List<JCAnnotation> nonNulls = findAnnotations(field, NON_NULL_PATTERN);
        List<JCAnnotation> nullables = findAnnotations(field, NULLABLE_PATTERN);

        List<JCAnnotation> delegates = findDelegatesAndRemoveFromField(field);

        List<JCAnnotation> annsOnMethod =
                        new ListBuffer<JCAnnotation>().toList().appendList(nonNulls).appendList(nullables);
        if (isFieldDeprecated(field) || deprecate) {
            annsOnMethod = annsOnMethod.prepend(
                            treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
        }

        JCMethodDecl decl = recursiveSetGeneratedBy(
                        treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod), methodName,
                                        treeMaker.Ident(field.toName("String")), methodGenericParams, parameters,
                                        throwsClauses, methodBody, annotationMethodDefaultValue),
                        source, field.getContext());

        decl.mods.annotations = decl.mods.annotations.appendList(delegates);

        return decl;
    }

    public JCMethodDecl createGetter(long access, JavacNode field, JavacTreeMaker treeMaker, JCTree source) {
        JCVariableDecl fieldNode = (JCVariableDecl) field.get();

        JCExpression methodType = copyType(treeMaker, fieldNode);
        Name methodName = field.toName(field.getName());

        List<JCStatement> statements;
        statements = createSimpleGetterBody(treeMaker, field);

        JCBlock methodBody = treeMaker.Block(0, statements);

        List<JCTypeParameter> methodGenericParams = List.nil();
        List<JCVariableDecl> parameters = List.nil();
        List<JCExpression> throwsClauses = List.nil();
        JCExpression annotationMethodDefaultValue = null;

        List<JCAnnotation> nonNulls = findAnnotations(field, NON_NULL_PATTERN);
        List<JCAnnotation> nullables = findAnnotations(field, NULLABLE_PATTERN);

        List<JCAnnotation> delegates = findDelegatesAndRemoveFromField(field);

        List<JCAnnotation> annsOnMethod =
                        new ListBuffer<JCAnnotation>().toList().appendList(nonNulls).appendList(nullables);
        if (isFieldDeprecated(field)) {
            annsOnMethod = annsOnMethod.prepend(
                            treeMaker.Annotation(genJavaLangTypeRef(field, "Deprecated"), List.<JCExpression>nil()));
        }

        JCMethodDecl decl = recursiveSetGeneratedBy(treeMaker.MethodDef(treeMaker.Modifiers(access, annsOnMethod),
                        methodName, methodType, methodGenericParams, parameters, throwsClauses, methodBody,
                        annotationMethodDefaultValue), source, field.getContext());

        decl.mods.annotations = decl.mods.annotations.appendList(delegates);

        return decl;
    }

    public static List<JCAnnotation> findDelegatesAndRemoveFromField(JavacNode field) {
        JCVariableDecl fieldNode = (JCVariableDecl) field.get();

        List<JCAnnotation> delegates = List.nil();
        for (JCAnnotation annotation : fieldNode.mods.annotations) {
            if (typeMatches(Delegate.class, field, annotation.annotationType)) {
                delegates = delegates.append(annotation);
            }
        }

        if (!delegates.isEmpty()) {
            ListBuffer<JCAnnotation> withoutDelegates = new ListBuffer<JCAnnotation>();
            for (JCAnnotation annotation : fieldNode.mods.annotations) {
                if (!delegates.contains(annotation)) {
                    withoutDelegates.append(annotation);
                }
            }
            fieldNode.mods.annotations = withoutDelegates.toList();
            field.rebuild();
        }
        return delegates;
    }

    public List<JCStatement> createSimpleGetterBody(JavacTreeMaker treeMaker, JavacNode field) {
        return List.<JCStatement>of(treeMaker.Return(createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD)));
    }

    public static final Map<TypeTag, String> TYPE_MAP;
    static {
        Map<TypeTag, String> m = new HashMap<TypeTag, String>();
        m.put(CTC_INT, "Integer");
        m.put(CTC_DOUBLE, "Double");
        m.put(CTC_FLOAT, "Float");
        m.put(CTC_SHORT, "Short");
        m.put(CTC_BYTE, "Byte");
        m.put(CTC_LONG, "Long");
        m.put(CTC_BOOLEAN, "Boolean");
        m.put(CTC_CHAR, "Character");
        TYPE_MAP = Collections.unmodifiableMap(m);
    }

    public JCExpression copyType(JavacTreeMaker treeMaker, JCVariableDecl fieldNode) {
        return fieldNode.type != null ? treeMaker.Type(fieldNode.type) : fieldNode.vartype;
    }
}
