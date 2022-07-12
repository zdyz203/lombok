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

import static lombok.javac.JavacTreeMaker.TypeTag.typeTag;
import static lombok.javac.handlers.JavacHandlerUtil.addImport;
import static lombok.javac.handlers.JavacHandlerUtil.deleteAnnotationIfNeccessary;
import static lombok.javac.handlers.JavacHandlerUtil.isSubClassOf;
import static lombok.javac.handlers.MetricHandlerUtil.addFixTags;
import static lombok.javac.handlers.MetricHandlerUtil.addMetricImport;
import static lombok.javac.handlers.MetricHandlerUtil.resultName;

import java.util.Collection;

import javax.lang.model.type.TypeKind;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.Gauge;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

/**
 * Handles the {@code lombok.Coded} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleGauge extends JavacAnnotationHandler<Gauge> {

    @Override
    public void handle(AnnotationValues<Gauge> annotation, JCAnnotation ast, JavacNode annotationNode) {
        addMetricImport(annotationNode);
        addImport(annotationNode, "java.util.function", "Supplier");

        deleteAnnotationIfNeccessary(annotationNode, Gauge.class);

        JavacNode node = JavacHandlerUtil.upToTypeNode(annotationNode);
        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl)node.get();

        JCTree annotatedNode = annotationNode.up().get();
        JavacTreeMaker maker = annotationNode.getTreeMaker();

        Gauge gauge = annotation.getInstance();

        try {
            boolean isStatic = isStatic(annotationNode, annotatedNode);
            if (isStatic) {
                JCTree.JCStatement addGauge = createAddGaugeStatement(annotationNode, annotatedNode, gauge, true);
                JCTree.JCBlock block = maker.Block(Flags.STATIC, List.of(addGauge));
                ListBuffer<JCTree> newDefs = new ListBuffer<JCTree>();
                newDefs.appendList(classDecl.defs);
                newDefs.append(block);
                classDecl.defs = newDefs.toList();
            } else {
                for (JCTree.JCMethodDecl methodDecl : findConstructorsWithoutThisCall(classDecl)) {
                    JCTree.JCBlock body = methodDecl.getBody();
                    JCTree.JCStatement addGauge = createAddGaugeStatement(annotationNode, annotatedNode, gauge, false);
                    body.stats = injectAddGaugeStatement(body.stats, addGauge);
                }
            }
        } catch (CheckException e) {

        }
    }

    private JCTree.JCStatement createAddGaugeStatement(JavacNode annotationNode, JCTree annotatedNode, Gauge gauge,
            boolean isStatic) {
        JavacTreeMaker maker = annotationNode.getTreeMaker();

        JavacNode node = JavacHandlerUtil.upToTypeNode(annotationNode);
        JCTree.JCClassDecl classDecl = (JCTree.JCClassDecl)node.get();
        JCTree.JCExpression metrics = maker.Ident(node.toName("Metrics"));
        List<JCTree.JCExpression> eNil = List.<JCTree.JCExpression>nil();

        JCTree.JCExpression restype;
        JCTree.JCExpression call;
        if (annotatedNode instanceof JCTree.JCMethodDecl) {
            JCTree.JCMethodDecl method = (JCTree.JCMethodDecl)annotatedNode;
            call = maker.Apply(eNil, maker.Ident(method.name), eNil);
            restype = method.restype;
        } else if (annotatedNode instanceof JCTree.JCVariableDecl) {
            JCTree.JCVariableDecl var = (JCTree.JCVariableDecl)annotatedNode;
            JCTree.JCIdent className = maker.Ident(node.toName(classDecl.name.toString()));
            if (isStatic) {
                call = maker.Select(className, var.name);
            } else {
                call = maker.Select(maker.Select(className, node.toName("this")), var.name);
            }
            restype = var.vartype;
        } else {
            annotationNode.addError("@Gauge only support field and method");
            throw new CheckException();
        }

        JCTree.JCExpression expression = supplierExpression(annotationNode, restype, call, gauge);
        ListBuffer<JCTree.JCExpression> args = new ListBuffer<JCTree.JCExpression>();
        args.append(maker.Literal(gauge.name()));
        args.append(expression);
        metrics = addFixTags(annotationNode, metrics, gauge.fixTags());

        return maker.Exec(maker.Apply(eNil, maker.Select(metrics, node.toName("addGauge")), args.toList()));
    }

    private boolean isStatic(JavacNode annotationNode, JCTree annotatedNode) {
        if (annotatedNode instanceof JCTree.JCMethodDecl) {
            JCTree.JCMethodDecl method = (JCTree.JCMethodDecl)annotatedNode;
            if (!method.params.isEmpty()) {
                annotationNode.addError("@Gauge only support non-parameter method");
                throw new CheckException();
            }
            return isStatic(method.getModifiers());
        } else if (annotatedNode instanceof JCTree.JCVariableDecl) {
            JCTree.JCVariableDecl var = (JCTree.JCVariableDecl)annotatedNode;
            return isStatic(var.getModifiers());
        } else {
            annotationNode.addError("@Gauge only support field or method");
            throw new CheckException();
        }
    }

    /**
     * 分两种情况，有或者没有super调用。
     * 有的话，要把addGauge放在第二行
     * 否则，把addGauge放在第一行
     */
    private List<JCTree.JCStatement> injectAddGaugeStatement(List<JCTree.JCStatement> stats,
            JCTree.JCStatement addGauge) {
        ListBuffer<JCTree.JCStatement> result = new ListBuffer<JCTree.JCStatement>();
        if (firstStatementIsMethodInvocation(stats, "super")) {
            result.append(stats.get(0));
            result.append(addGauge);
            for (int i = 1; i < stats.size(); ++i) {
                result.append(stats.get(i));
            }
        } else {
            result.append(addGauge);
            result.appendList(stats);
        }
        return result.toList();
    }

    /**
     * 找到所有没有this调用的构造函数
     * this调用是指在一个构造函数中调用了另一个构造函数。
     * 这种构造函数我们不注入addGauge代码，因为被调用的构造函数里面已经注入了addGauge代码了。
     */
    private List<JCTree.JCMethodDecl> findConstructorsWithoutThisCall(JCTree.JCClassDecl classDecl) {
        ListBuffer<JCTree.JCMethodDecl> result = new ListBuffer<JCTree.JCMethodDecl>();
        for (JCTree def : classDecl.defs) {
            if (!(def instanceof JCTree.JCMethodDecl)) {
                continue;
            }
            JCTree.JCMethodDecl methodDecl = (JCTree.JCMethodDecl)def;
            if (!"<init>".equals(methodDecl.name.toString())) {
                continue;
            }
            List<JCTree.JCStatement> stats = methodDecl.body.stats;
            if (firstStatementIsMethodInvocation(stats, "this")) {
                continue;
            }
            result.append(methodDecl);
        }
        return result.toList();
    }

    private boolean firstStatementIsMethodInvocation(List<JCTree.JCStatement> stats, String methodName) {
        if (stats.isEmpty()) {
            return false;
        }
        JCTree.JCStatement firstStatement = stats.get(0);
        if (!(firstStatement instanceof JCTree.JCExpressionStatement)) {
            return false;
        }
        JCTree.JCExpressionStatement expressionStatement = (JCTree.JCExpressionStatement)firstStatement;
        if (!(expressionStatement.getExpression() instanceof JCTree.JCMethodInvocation)) {
            return false;
        }
        JCTree.JCMethodInvocation invocation = (JCTree.JCMethodInvocation)expressionStatement.getExpression();
        if (!(invocation.getMethodSelect() instanceof JCTree.JCIdent)) {
            return false;
        }
        JCTree.JCIdent ident = (JCTree.JCIdent)invocation.getMethodSelect();
        return methodName.equals(ident.getName().toString());
    }

    private boolean checkModifiers(JavacNode node, JCTree.JCModifiers modifiers) {
        if ((modifiers.flags & Flags.STATIC) == 0) {
            node.addError("@Gauge only support static field and static method");
            return false;
        }
        return true;
    }

    private boolean isStatic(JCTree.JCModifiers modifiers) {
        return (modifiers.flags & Flags.STATIC) == Flags.STATIC;
    }

    private JCTree.JCExpression metricTypeExpression(JavacNode node, JCTree.JCExpression resType) {
        JavacTreeMaker maker = node.getTreeMaker();
        JCTree.JCIdent pkg = maker.Ident(node.toName("java.lang"));
        if (resType instanceof JCTree.JCArrayTypeTree) {
            return maker.Select(pkg, node.toName("Integer"));
        } else if (resType instanceof JCTree.JCPrimitiveTypeTree) {
            TypeKind typeKind = ((JCTree.JCPrimitiveTypeTree)resType).getPrimitiveTypeKind();
            switch (typeKind) {
                case BOOLEAN:
                    return maker.Select(pkg, node.toName("Boolean"));
                case BYTE:
                    return maker.Select(pkg, node.toName("Byte"));
                case SHORT:
                    return maker.Select(pkg, node.toName("Short"));
                case INT:
                    return maker.Select(pkg, node.toName("Integer"));
                case LONG:
                    return maker.Select(pkg, node.toName("Long"));
                case FLOAT:
                    return maker.Select(pkg, node.toName("Float"));
                case DOUBLE:
                    return maker.Select(pkg, node.toName("Double"));
                default:
                    node.addError(
                            "@Gauge only support type of [boolean,byte,short,int,long,float,double,Number,Array,Collection], but is "
                                    + resType.type.toString());
                    throw new CheckException();
            }
        } else if (isSubClassOf(resType.type, Number.class)) {
            return maker.Select(pkg, node.toName("Double"));
        } else if (isSubClassOf(resType.type, Collection.class)) {
            return maker.Select(pkg, node.toName("Integer"));
        } else {
            node.addError(
                    "@Gauge only support type of [boolean,byte,short,int,long,float,double,Number,Array,Collection], but is "
                            + resType.type.toString());
            throw new CheckException();
        }
    }

    private JCTree.JCExpression metricValueExpression(JavacNode node, Name name, JCTree.JCExpression resType) {
        JavacTreeMaker maker = node.getTreeMaker();
        if (resType instanceof JCTree.JCArrayTypeTree) {
            return maker.Select(maker.Ident(name), node.toName("length"));
        } else if (resType instanceof JCTree.JCPrimitiveTypeTree) {
            return maker.Ident(name);
        } else if (isSubClassOf(resType.type, Number.class)) {
            List<JCTree.JCExpression> nil = List.<JCTree.JCExpression>nil();
            JCTree.JCFieldAccess size = maker.Select(maker.Ident(name), node.toName("doubleValue"));
            return maker.Apply(nil, size, nil);
        } else {
            List<JCTree.JCExpression> nil = List.<JCTree.JCExpression>nil();
            JCTree.JCFieldAccess size = maker.Select(maker.Ident(name), node.toName("size"));
            return maker.Apply(nil, size, nil);
        }
    }

    private JCTree.JCExpression metricDefaultExpression(JavacNode node, Gauge gauge, JCTree.JCExpression resType) {
        JavacTreeMaker maker = node.getTreeMaker();
        if (resType instanceof JCTree.JCArrayTypeTree) {
            return maker.Literal(Javac.CTC_INT, (int)gauge.nullValue());
        } else if (resType instanceof JCTree.JCPrimitiveTypeTree) {
            return maker.Literal(Javac.CTC_LONG, 0);
        } else if (isSubClassOf(resType.type, Number.class)) {
            return maker.Literal(Javac.CTC_DOUBLE, gauge.nullValue());
        } else {
            return maker.Literal(Javac.CTC_INT, (int)gauge.nullValue());
        }
    }

    private JCTree.JCExpression supplierExpression(JavacNode node, JCTree.JCExpression resType,
            JCTree.JCExpression valueExpression, Gauge gauge) {
        JavacTreeMaker maker = node.getTreeMaker();

        List<JCTree.JCExpression> eNil = List.<JCTree.JCExpression>nil();
        List<JCTree.JCTypeParameter> pNil = List.<JCTree.JCTypeParameter>nil();
        List<JCTree.JCVariableDecl> vNil = List.<JCTree.JCVariableDecl>nil();

        JCTree.JCExpression type = metricTypeExpression(node, resType);
        Name name = resultName(node);

        JCTree.JCBlock body;

        if (resType instanceof JCTree.JCPrimitiveTypeTree) {
            body = maker.Block(0, List.<JCTree.JCStatement>of(maker.Return(valueExpression)));
        } else {
            JCTree.JCBinary nullCondition = maker.Binary(JavacTreeMaker.TreeTag.treeTag("NE"),
                    maker.Ident(name), maker.Literal(typeTag("BOT"), null));
            JCTree.JCReturn thenStat = maker.Return(metricValueExpression(node, name, resType));
            JCTree.JCReturn elseStat = maker.Return(metricDefaultExpression(node, gauge, resType));
            JCTree.JCIf ifStat = maker.If(nullCondition, thenStat, elseStat);

            JCTree.JCVariableDecl varDef = maker.VarDef(maker.Modifiers(0), name, resType, valueExpression);

            body = maker.Block(0, List.of(varDef, ifStat));
        }

        JCTree methodDecl =
                maker.MethodDef(maker.Modifiers(Flags.PUBLIC), node.toName("get"), type, pNil, vNil, eNil, body, null);
        JCTree.JCClassDecl classDecl =
                maker.ClassDef(maker.Modifiers(0), node.toName(""), pNil, null, eNil, List.of(methodDecl));
        JCTree.JCTypeApply clazz = maker.TypeApply(maker.Ident(node.toName("Supplier")), List.of(type));
        return maker.NewClass(null, eNil, clazz, eNil, classDecl);
    }

}
