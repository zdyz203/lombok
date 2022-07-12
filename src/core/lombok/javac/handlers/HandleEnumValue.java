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

import lombok.EnumValue;
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
 * Handles the {@code lombok.EnumValue} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleEnumValue extends JavacAnnotationHandler<EnumValue> {

    @Override
    public void handle(AnnotationValues<EnumValue> annotation, JCAnnotation ast, JavacNode annotationNode) {

        deleteAnnotationIfNeccessary(annotationNode, EnumValue.class);
        deleteImportFromCompilationUnit(annotationNode, "lombok.AccessLevel");

        Type.ClassType enumClassType = getEnumClassType(annotationNode);
        if(!checkSuperClassType(enumClassType)){
            annotationNode.addError("@EnumValue must has a enum type as its value");
        }

        String className = enumClassType.tsym.getSimpleName().toString();
        String classFullName = enumClassType.tsym.getQualifiedName().toString();
        String packageName = classFullName.substring(0, classFullName.lastIndexOf('.'));
        addImport(annotationNode.top(), packageName, className, classFullName);

        JavacNode node = annotationNode.up();
        if (node == null)
            return;

        Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
        createGetterForFields(fields, annotationNode, true, className);
        createSetterForFields(fields, annotationNode, true, className);
    }

    public static String getEnumClassName(JavacNode annotationNode){
        Type.ClassType enumClassType = getEnumClassType(annotationNode);
        return enumClassType.tsym.getSimpleName().toString();
    }

    private boolean checkSuperClassType(Type.ClassType enumClassType) {
        return enumClassType.supertype_field.tsym.getQualifiedName().toString().equals("java.lang.Enum");
    }

    private static Type.ClassType getEnumClassType(JavacNode annotationNode){
        JCAnnotation jcTree = (JCAnnotation)annotationNode.get();
        JCTree.JCAssign jcAssign = (JCTree.JCAssign)jcTree.getArguments().get(0);
        JCTree.JCFieldAccess jcFieldAccess = (JCTree.JCFieldAccess)jcAssign.getExpression();
        return (Type.ClassType)jcFieldAccess.type.getTypeArguments().get(0);
    }

    private void addImport(JavacNode typeNode, String packageName, String simpleName, String fullName) {
        JCTree.JCCompilationUnit unit = (JCTree.JCCompilationUnit) typeNode.get();
        for (JCTree def : unit.defs) {
            if (def instanceof JCTree.JCImport) {
                JCTree.JCImport imp0rt = (JCTree.JCImport) def;
                if (!imp0rt.staticImport
                                && imp0rt.qualid.toString().equals(fullName))
                    return;
            }
        }
        JavacTreeMaker maker = typeNode.getTreeMaker();
        JCTree.JCIdent packAge = maker.Ident(typeNode.toName(packageName));
        JCTree.JCFieldAccess codeUtil = maker.Select(packAge, typeNode.toName(simpleName));
        unit.defs = unit.defs.append(maker.Import(codeUtil, false));
    }

    public void createSetterForFields(Collection<JavacNode> fieldNodes, JavacNode annotationNode, boolean whineIfExists, String enumClassName) {
        AccessLevel level = getAccessLevelFromSetter(AccessLevel.PUBLIC, upToTypeNode(annotationNode));
        for (JavacNode fieldNode : fieldNodes) {
            level = getAccessLevelFromSetter(level, fieldNode);
            if (level == AccessLevel.NONE)
                continue;
            createSetterForField(level, fieldNode, annotationNode, whineIfExists, enumClassName);
        }
    }

    public void createSetterForField(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, String enumClassName) {
        if (fieldNode.getKind() != Kind.FIELD) {
            fieldNode.addError("@EnumValue is only supported on a field.");
            return;
        }

        if(!checkFiledType(fieldNode))
            return;

        JCVariableDecl fieldDecl = (JCVariableDecl) fieldNode.get();

        if ((fieldDecl.mods.flags & Flags.FINAL) != 0) {
            fieldNode.addWarning("Not generating setter for this field: Setters cannot be generated for final fields.");
            return;
        }

        addEncodeSetter(level, fieldNode, sourceNode, whineIfExists, fieldDecl, enumClassName);
    }

    private boolean checkFiledType(JavacNode fieldNode) {
        JCExpression type = ((JCVariableDecl)fieldNode.get()).vartype;
        if(!(type instanceof JCTree.JCPrimitiveTypeTree)){
            fieldNode.addError("@EnumValue is only supported on [byte,char,short,int]");
            return false;
        }
        switch (((JCTree.JCPrimitiveTypeTree)type).getPrimitiveTypeKind()){
            case BYTE:
            case CHAR:
            case SHORT:
            case INT:
                break;
            default:
                fieldNode.addError("@EnumValue is only supported on [byte,char,short,int]");
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

    private void addEncodeSetter(AccessLevel level, JavacNode fieldNode, JavacNode sourceNode, boolean whineIfExists, JCVariableDecl fieldDecl, String enumClassName) {
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
        boolean shouldReturnThis = shouldReturnThis(fieldNode);
        String setterName = toSetterName(fieldNode);
        JCMethodDecl createdSetter = createEnumSetter(access, false, fieldNode, fieldNode.getTreeMaker(), setterName, shouldReturnThis, sourceNode, enumClassName);
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

    public static JCMethodDecl createEnumSetter(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker,
                                                String setterName, boolean shouldReturnThis, JavacNode source, String enumClassName) {

        if (setterName == null)
            return null;

        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();

        String getValueMethodName = "getValue";
        JCTree.JCFieldAccess getValue = treeMaker.Select(treeMaker.Ident(fieldDecl.name), field.toName(getValueMethodName));
        JCMethodInvocation methodCall = treeMaker.Apply(List.<JCExpression>nil(), getValue, List.<JCExpression>nil());
        JCExpression fieldRef = createFieldAccessor(treeMaker, field, FieldAccess.ALWAYS_FIELD);
        JCTree.JCAssign assign = treeMaker.Assign(fieldRef, methodCall);

        ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();
        List<JCAnnotation> nonNulls = findAnnotations(field, NON_NULL_PATTERN);
        List<JCAnnotation> nullables = findAnnotations(field, NULLABLE_PATTERN);

        Name methodName = field.toName(setterName);
        List<JCAnnotation> annsOnParam = new ListBuffer<JCAnnotation>().toList().appendList(nonNulls).appendList(nullables);

        long flags = JavacHandlerUtil.addFinalIfNeeded(Flags.PARAMETER, field.getContext());
        JCVariableDecl param = treeMaker.VarDef(treeMaker.Modifiers(flags, annsOnParam), fieldDecl.name, treeMaker.Ident(field.toName(enumClassName)), null);

        if (nonNulls.isEmpty()) {
            statements.append(treeMaker.Exec(assign));
        } else {
            JCStatement nullCheck = generateNullCheck(treeMaker, field, source);
            if (nullCheck != null) statements.append(nullCheck);
            statements.append(treeMaker.Exec(assign));
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
                    boolean whineIfExists, String enumClassName) {
        AccessLevel level = getAccessLevelFromGetter(AccessLevel.PUBLIC, upToTypeNode(annotationNode));
        for (JavacNode fieldNode : fieldNodes) {
            level = getAccessLevelFromGetter(level, fieldNode);
            if (level == AccessLevel.NONE)
                continue;
            createGetterForField(level, fieldNode, annotationNode, whineIfExists, enumClassName);
        }
    }

    public void createGetterForField(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists, String enumClassName) {
        if (fieldNode.getKind() != Kind.FIELD) {
            source.addError("@EnumValue is only supported on a field.");
            return;
        }

        if(!checkFiledType(fieldNode))
            return;

        String name = fieldNode.getName();

        if (name == null) {
            source.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
            return;
        }

		// add decode getter
		addGetter(level, fieldNode, source, whineIfExists, enumClassName);

    }

	private void addGetter(AccessLevel level, JavacNode fieldNode, JavacNode source, boolean whineIfExists, String enumClassName) {

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

		injectMethod(fieldNode.up(), createEnumGetter(access, false, fieldNode, fieldNode.getTreeMaker(), source.get(), enumClassName),
				List.<Type>nil(), getMirrorForFieldType(fieldNode));
	}

	public static JCMethodDecl createEnumGetter(long access, boolean deprecate, JavacNode field, JavacTreeMaker treeMaker, JCTree source, String enumClassName) {
        Name methodName = field.toName(toGetterName(field));

        List<JCStatement> statements;
        JCVariableDecl fieldDecl = (JCVariableDecl) field.get();

        JCTree.JCFieldAccess of = treeMaker.Select(treeMaker.Ident(field.toName(enumClassName)), field.toName("of"));
        JCTree.JCIdent param = treeMaker.Ident(fieldDecl.name);
        JCMethodInvocation ofMethodCall = treeMaker.Apply(List.<JCExpression>nil(), of, List.<JCExpression>of(param));
        JCTree.JCReturn returnStatement = treeMaker.Return(ofMethodCall);
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
                                        treeMaker.Ident(field.toName(enumClassName)), methodGenericParams, parameters,
                                        throwsClauses, methodBody, annotationMethodDefaultValue),
                        source, field.getContext());

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
