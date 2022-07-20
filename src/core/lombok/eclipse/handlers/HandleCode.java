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
package lombok.eclipse.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.eclipse.Eclipse.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.*;
import static lombok.eclipse.handlers.EclipseHandlerUtil.toGetterName;
import static lombok.eclipse.handlers.EclipseHandlerUtil.toSetterName;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeIds;
import org.mangosdk.spi.ProviderFor;

import lombok.AccessLevel;
import lombok.Coded;
import lombok.core.AST.Kind;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.agent.PatchDelegate;
import lombok.eclipse.handlers.EclipseHandlerUtil.FieldAccess;
import lombok.experimental.Delegate;

/**
 * Handles the {@code lombok.Coded} annotation for javac.
 */
@ProviderFor(EclipseAnnotationHandler.class) public class HandleCode extends EclipseAnnotationHandler<Coded> {
	private static final Annotation[] EMPTY_ANNOTATIONS_ARRAY = new Annotation[0];
	// com.ctrip.flight.intl.common.utils.CodeUtil
	private static final char[][] CODE_UTIL = {"com".toCharArray(), "ctrip".toCharArray(), "flight".toCharArray(), "intl".toCharArray(), "common".toCharArray(), "utils".toCharArray(), "CodeUtil".toCharArray()};
	
	@Override public void handle(AnnotationValues<Coded> annotation, Annotation ast, EclipseNode annotationNode) {
		
		EclipseNode node = annotationNode.up();
		if (node == null) {
			return;
		}
		if (!node.getKind().equals(Kind.FIELD)) {
			return;
		}
		List<Annotation> onMethod = unboxAndRemoveAnnotationParameter(ast, "onMethod", "@Coded(onMethod", annotationNode);
		List<Annotation> onParam = unboxAndRemoveAnnotationParameter(ast, "onParam", "@Coded(onParam", annotationNode);
		createGetterForFields(lombok.AccessLevel.PUBLIC, annotationNode.upFromAnnotationToFields(), annotationNode, annotationNode.get(), true, onMethod);
		createSetterForFields(lombok.AccessLevel.PUBLIC, annotationNode.upFromAnnotationToFields(), annotationNode, true, onMethod, onParam);
	}
	
	public void createGetterForFields(AccessLevel level, Collection<EclipseNode> fieldNodes, EclipseNode errorNode, ASTNode source, boolean whineIfExists, List<Annotation> onMethod) {
		for (EclipseNode fieldNode : fieldNodes) {
			createGetterForField(level, fieldNode, errorNode, source, whineIfExists, onMethod);
		}
	}
	
	public void createSetterForFields(AccessLevel level, Collection<EclipseNode> fieldNodes, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam) {
		for (EclipseNode fieldNode : fieldNodes) {
			createSetterForField(level, fieldNode, sourceNode, whineIfExists, onMethod, onParam);
		}
	}
	
	public void createGetterForField(AccessLevel level, EclipseNode fieldNode, EclipseNode errorNode, ASTNode source, boolean whineIfExists, List<Annotation> onMethod) {
		if (fieldNode.getKind() != Kind.FIELD) {
			errorNode.addError("@Coded is only supported on a field.");
			return;
		}
		
		if (!checkFiledType(fieldNode, source)) {
			return;
		}
		
		// add normal getter
		addNormalGetter(level, fieldNode, errorNode, source, whineIfExists, onMethod);
		// add decode getter
		addDecodeGetter(level, fieldNode, errorNode, source, whineIfExists, onMethod);
	}
	
	public void createSetterForField(AccessLevel level, EclipseNode fieldNode, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam) {
		if (fieldNode.getKind() != Kind.FIELD) {
			sourceNode.addError("@Coded is only supported on a class or a field.");
			return;
		}
		
		ASTNode source = sourceNode.get();
		
		if (!checkFiledType(fieldNode, source)) {
			return;
		}
		
		addNormalSetter(level, fieldNode, sourceNode, whineIfExists, onMethod, onParam);
		addEncodeSetter(level, fieldNode, sourceNode, whineIfExists, onMethod, onParam);
	}
	
	public void addNormalSetter(AccessLevel level, EclipseNode fieldNode, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam) {
		String setterName = fieldNode.getName();
		if (setterName == null) {
			fieldNode.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		boolean shouldReturnThis = shouldReturnThis(fieldNode);
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		switch (methodExists(setterName, fieldNode, false, 1)) {
		case EXISTS_BY_LOMBOK:
			return;
		case EXISTS_BY_USER:
			if (whineIfExists) {
				fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists", setterName));
			}
			return;
		default:
		case NOT_EXISTS:
			// continue scanning the other alt names.
		}
		
		MethodDeclaration method = createSetter((TypeDeclaration) fieldNode.up().get(), false, fieldNode, setterName, null, shouldReturnThis, modifier, sourceNode, onMethod, onParam);
		injectMethod(fieldNode.up(), method);
	}
	
	public void addEncodeSetter(AccessLevel level, EclipseNode fieldNode, EclipseNode sourceNode, boolean whineIfExists, List<Annotation> onMethod, List<Annotation> onParam) {
		String setterName = toSetterName(fieldNode, false);
		if (setterName == null) {
			fieldNode.addWarning("Not generating setter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		switch (methodExists(setterName, fieldNode, false, 1)) {
		case EXISTS_BY_LOMBOK:
			return;
		case EXISTS_BY_USER:
			if (whineIfExists) {
				fieldNode.addWarning(String.format("Not generating %s(): A method with that name already exists", setterName));
			}
			return;
		default:
		case NOT_EXISTS:
			// continue scanning the other alt names.
		}
		
		MethodDeclaration method = createEncodedSetter((TypeDeclaration) fieldNode.up().get(), false, fieldNode, setterName, null, modifier, sourceNode, onMethod, onParam);
		injectMethod(fieldNode.up(), method);
	}
	
	/**
	 * fieldname() return fieldType;
	 * 
	 * @param level
	 * @param fieldNode
	 * @param errorNode
	 * @param source
	 * @param whineIfExists
	 * @param lazy
	 * @param onMethod
	 */
	public void addNormalGetter(AccessLevel level, EclipseNode fieldNode, EclipseNode errorNode, ASTNode source, boolean whineIfExists, List<Annotation> onMethod) {
		String getterName = fieldNode.getName();
		
		if (getterName == null) {
			errorNode.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		switch (methodExists(getterName, fieldNode, false, 0)) {
		case EXISTS_BY_LOMBOK:
			return;
		case EXISTS_BY_USER:
			if (whineIfExists) {
				errorNode.addWarning(String.format("Not generating %s(): A method with that name already exists", getterName));
			}
			return;
		default:
		case NOT_EXISTS:
			// continue scanning the other alt names.
		}
		
		MethodDeclaration method = createGetter((TypeDeclaration) fieldNode.up().get(), fieldNode, getterName, modifier, source, onMethod);
		
		injectMethod(fieldNode.up(), method);
	}
	
	public void addDecodeGetter(AccessLevel level, EclipseNode fieldNode, EclipseNode errorNode, ASTNode source, boolean whineIfExists, List<Annotation> onMethod) {
		String getterName = toGetterName(fieldNode, false);
		
		if (getterName == null) {
			errorNode.addWarning("Not generating getter for this field: It does not fit your @Accessors prefix list.");
			return;
		}
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		int modifier = toEclipseModifier(level) | (field.modifiers & ClassFileConstants.AccStatic);
		
		switch (methodExists(getterName, fieldNode, false, 0)) {
		case EXISTS_BY_LOMBOK:
			return;
		case EXISTS_BY_USER:
			if (whineIfExists) {
				errorNode.addWarning(String.format("Not generating %s(): A method with that name already exists", getterName));
			}
			return;
		default:
		case NOT_EXISTS:
			// continue scanning the other alt names.
		}
		
		MethodDeclaration method = createDecodeGetter((TypeDeclaration) fieldNode.up().get(), fieldNode, getterName, modifier, source, onMethod);
		
		injectMethod(fieldNode.up(), method);
	}
	
	static MethodDeclaration createSetter(TypeDeclaration parent, boolean deprecate, EclipseNode fieldNode, String name, char[] booleanFieldToSet, boolean shouldReturnThis, int modifier, EclipseNode sourceNode, List<Annotation> onMethod, List<Annotation> onParam) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		ASTNode source = sourceNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		if (shouldReturnThis) {
			method.returnType = cloneSelfType(fieldNode, source);
		}
		
		if (method.returnType == null) {
			method.returnType = TypeReference.baseTypeReference(TypeIds.T_void, 0);
			method.returnType.sourceStart = pS;
			method.returnType.sourceEnd = pE;
			shouldReturnThis = false;
		}
		Annotation[] deprecated = null;
		if (isFieldDeprecated(fieldNode) || deprecate) {
			deprecated = new Annotation[] {generateDeprecatedAnnotation(source)};
		}
		method.annotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), deprecated);
		Argument param = new Argument(field.name, p, copyType(field.type, source), Modifier.FINAL);
		param.sourceStart = pS;
		param.sourceEnd = pE;
		method.arguments = new Argument[] {param};
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		NameReference fieldNameRef = new SingleNameReference(field.name, p);
		Assignment assignment = new Assignment(fieldRef, fieldNameRef, (int) p);
		assignment.sourceStart = pS;
		assignment.sourceEnd = assignment.statementEnd = pE;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		Annotation[] nonNulls = findAnnotations(field, NON_NULL_PATTERN);
		Annotation[] nullables = findAnnotations(field, NULLABLE_PATTERN);
		List<Statement> statements = new ArrayList<Statement>(5);
		if (nonNulls.length == 0) {
			statements.add(assignment);
		} else {
			Statement nullCheck = generateNullCheck(field, sourceNode);
			if (nullCheck != null) statements.add(nullCheck);
			statements.add(assignment);
		}
		
		if (booleanFieldToSet != null) {
			statements.add(new Assignment(new SingleNameReference(booleanFieldToSet, p), new TrueLiteral(pS, pE), pE));
		}
		
		if (shouldReturnThis) {
			ThisReference thisRef = new ThisReference(pS, pE);
			ReturnStatement returnThis = new ReturnStatement(thisRef, pS, pE);
			statements.add(returnThis);
		}
		method.statements = statements.toArray(new Statement[0]);
		param.annotations = copyAnnotations(source, nonNulls, nullables, onParam.toArray(new Annotation[0]));
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
	static MethodDeclaration createEncodedSetter(TypeDeclaration parent, boolean deprecate, EclipseNode fieldNode, String name, char[] booleanFieldToSet, int modifier, EclipseNode sourceNode, List<Annotation> onMethod, List<Annotation> onParam) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		ASTNode source = sourceNode.get();
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		
		method.returnType = TypeReference.baseTypeReference(TypeIds.T_void, 0);
		method.returnType.sourceStart = pS;
		method.returnType.sourceEnd = pE;
		
		Annotation[] deprecated = null;
		if (isFieldDeprecated(fieldNode) || deprecate) {
			deprecated = new Annotation[] {generateDeprecatedAnnotation(source)};
		}
		method.annotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), deprecated);
		TypeReference stringRef = new QualifiedTypeReference(TypeConstants.JAVA_LANG_STRING, new long[] {p, p, p});
		setGeneratedBy(stringRef, source);
		Argument param = new Argument(field.name, p, stringRef, Modifier.FINAL);
		param.sourceStart = pS;
		param.sourceEnd = pE;
		method.arguments = new Argument[] {param};
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		NameReference fieldNameRef = new SingleNameReference(field.name, p);
		
		MessageSend encodeMethod = new MessageSend();
		encodeMethod.sourceStart = pS;
		encodeMethod.sourceEnd = pE;
		encodeMethod.receiver = generateQualifiedNameRef(source, CODE_UTIL);
		encodeMethod.arguments = new Expression[] {fieldNameRef};
		setGeneratedBy(encodeMethod.arguments[0], source);
		
		TypeReference fieldType = copyType(field.type, source);
		String suspect = new String(fieldType.getTypeName()[fieldType.getTypeName().length - 1]);
		String encodeMethodName = null;
		if ("short".equals(suspect)) {
			encodeMethodName = "encodeAsShort";
		} else if ("int".equals(suspect)) {
			encodeMethodName = "encodeAsInteger";
		} else if ("long".equals(suspect)) {
			encodeMethodName = "encodeAsLong";
		}
		encodeMethod.selector = (encodeMethodName).toCharArray();
		setGeneratedBy(encodeMethod, source);
		
		Assignment assignment = new Assignment(fieldRef, encodeMethod, (int) p);
		assignment.sourceStart = pS;
		assignment.sourceEnd = assignment.statementEnd = pE;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		Annotation[] nonNulls = findAnnotations(field, NON_NULL_PATTERN);
		Annotation[] nullables = findAnnotations(field, NULLABLE_PATTERN);
		List<Statement> statements = new ArrayList<Statement>(5);
		if (nonNulls.length == 0) {
			statements.add(assignment);
		} else {
			Statement nullCheck = generateNullCheck(field, sourceNode);
			if (nullCheck != null) statements.add(nullCheck);
			statements.add(assignment);
		}
		
		if (booleanFieldToSet != null) {
			statements.add(new Assignment(new SingleNameReference(booleanFieldToSet, p), new TrueLiteral(pS, pE), pE));
		}
		
		method.statements = statements.toArray(new Statement[0]);
		
		param.annotations = copyAnnotations(source, nonNulls, nullables, onParam.toArray(new Annotation[0]));
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
	public MethodDeclaration createGetter(TypeDeclaration parent, EclipseNode fieldNode, String name, int modifier, ASTNode source, List<Annotation> onMethod) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		
		// Remember the type; lazy will change it;
		TypeReference returnType = copyType(((FieldDeclaration) fieldNode.get()).type, source);
		
		Statement[] statements = createSimpleGetterBody(source, fieldNode);
		
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		method.returnType = returnType;
		method.annotations = null;
		method.arguments = null;
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		method.statements = statements;
		
		EclipseHandlerUtil.registerCreatedLazyGetter((FieldDeclaration) fieldNode.get(), method.selector, returnType);
		
		/*
		 * Generate annotations that must be put on the generated method, and
		 * attach them.
		 */ {
			Annotation[] deprecated = null;
			if (isFieldDeprecated(fieldNode)) {
				deprecated = new Annotation[] {generateDeprecatedAnnotation(source)};
			}
			
			method.annotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), findAnnotations(field, NON_NULL_PATTERN), findAnnotations(field, NULLABLE_PATTERN), findDelegatesAndMarkAsHandled(fieldNode), deprecated);
		}
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
	public MethodDeclaration createDecodeGetter(TypeDeclaration parent, EclipseNode fieldNode, String name, int modifier, ASTNode source, List<Annotation> onMethod) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		
		MethodDeclaration method = new MethodDeclaration(parent.compilationResult);
		method.modifiers = modifier;
		method.returnType = new QualifiedTypeReference(TypeConstants.JAVA_LANG_STRING, new long[] {p, p, p});
		method.annotations = null;
		method.arguments = null;
		method.selector = name.toCharArray();
		method.binding = null;
		method.thrownExceptions = null;
		method.typeParameters = null;
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		method.bodyStart = method.declarationSourceStart = method.sourceStart = source.sourceStart;
		method.bodyEnd = method.declarationSourceEnd = method.sourceEnd = source.sourceEnd;
		
		MessageSend arrayToString = new MessageSend();
		arrayToString.sourceStart = pS;
		arrayToString.sourceEnd = pE;
		arrayToString.receiver = generateQualifiedNameRef(source, CODE_UTIL);
		Expression fieldAccessor = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		arrayToString.arguments = new Expression[] {fieldAccessor};
		setGeneratedBy(arrayToString.arguments[0], source);
		arrayToString.selector = ("decode").toCharArray();
		setGeneratedBy(arrayToString, source);
		
		ReturnStatement returnStatement = new ReturnStatement(arrayToString, pS, pE);
		setGeneratedBy(returnStatement, source);
		method.statements = new Statement[] {returnStatement};
		
		/*
		 * Generate annotations that must be put on the generated method, and
		 * attach them.
		 */ {
			Annotation[] deprecated = null;
			if (isFieldDeprecated(fieldNode)) {
				deprecated = new Annotation[] {generateDeprecatedAnnotation(source)};
			}
			
			method.annotations = copyAnnotations(source, onMethod.toArray(new Annotation[0]), findAnnotations(field, NON_NULL_PATTERN), findAnnotations(field, NULLABLE_PATTERN), findDelegatesAndMarkAsHandled(fieldNode), deprecated);
		}
		
		method.traverse(new SetGeneratedByVisitor(source), parent.scope);
		return method;
	}
	
	public static NameReference generateQualifiedNameRef(ASTNode source, char[]... varNames) {
		int pS = source.sourceStart, pE = source.sourceEnd;
		long p = (long) pS << 32 | pE;
		NameReference ref;
		if (varNames.length > 1) ref = new QualifiedNameReference(varNames, new long[varNames.length], pS, pE);
		else
			ref = new SingleNameReference(varNames[0], p);
		setGeneratedBy(ref, source);
		return ref;
	}
	
	public Statement[] createSimpleGetterBody(ASTNode source, EclipseNode fieldNode) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		Expression fieldRef = createFieldAccessor(fieldNode, FieldAccess.ALWAYS_FIELD, source);
		Statement returnStatement = new ReturnStatement(fieldRef, field.sourceStart, field.sourceEnd);
		return new Statement[] {returnStatement};
	}
	
	private boolean checkFiledType(EclipseNode fieldNode, ASTNode source) {
		FieldDeclaration field = (FieldDeclaration) fieldNode.get();
		if (!(field.type instanceof SingleTypeReference)) {
			fieldNode.addError("@Coded is only supported on short int long");
			return false;
		}
		TypeReference fieldType = copyType(field.type, source);
		String suspect = new String(fieldType.getTypeName()[fieldType.getTypeName().length - 1]);
		if (!("short".equals(suspect) || "int".equals(suspect) || "long".equals(suspect))) {
			fieldNode.addError("@Coded is only supported on short int long");
			return false;
		}
		return true;
	}
	
	public static Annotation[] findDelegatesAndMarkAsHandled(EclipseNode fieldNode) {
		List<Annotation> delegates = new ArrayList<Annotation>();
		for (EclipseNode child : fieldNode.down()) {
			if (annotationTypeMatches(Delegate.class, child)) {
				Annotation delegate = (Annotation) child.get();
				PatchDelegate.markHandled(delegate);
				delegates.add(delegate);
			}
		}
		return delegates.toArray(EMPTY_ANNOTATIONS_ARRAY);
	}
}
