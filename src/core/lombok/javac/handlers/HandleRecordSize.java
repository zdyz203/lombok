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

import static lombok.javac.handlers.JavacHandlerUtil.deleteAnnotationIfNeccessary;
import static lombok.javac.handlers.JavacHandlerUtil.isSubClassOf;
import static lombok.javac.handlers.JavacHandlerUtil.longLiteral;
import static lombok.javac.handlers.MetricHandlerUtil.addFixTags;
import static lombok.javac.handlers.MetricHandlerUtil.addMetricImport;
import static lombok.javac.handlers.MetricHandlerUtil.addVarTags;
import static lombok.javac.handlers.MetricHandlerUtil.appendResultDecl;
import static lombok.javac.handlers.MetricHandlerUtil.appendReturnStat;
import static lombok.javac.handlers.MetricHandlerUtil.callRealMethod;
import static lombok.javac.handlers.MetricHandlerUtil.catchBlocks;
import static lombok.javac.handlers.MetricHandlerUtil.catchThrowable;
import static lombok.javac.handlers.MetricHandlerUtil.createAndInjectRealMethod;
import static lombok.javac.handlers.MetricHandlerUtil.findExceptions;
import static lombok.javac.handlers.MetricHandlerUtil.resultName;

import java.util.Collection;

import javax.lang.model.type.TypeKind;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.RecordSize;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

/**
 * Handles the {@code lombok.Coded} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleRecordSize extends JavacAnnotationHandler<RecordSize> {

    private static final String REAL_METHOD_SUFFIX = "size";

    @Override
    public void handle(AnnotationValues<RecordSize> annotation, JCAnnotation ast, JavacNode annotationNode) {
        addMetricImport(annotationNode);

        deleteAnnotationIfNeccessary(annotationNode, RecordSize.class);

        JavacNode method = annotationNode.up();

        if (!(method.get() instanceof JCTree.JCMethodDecl)) {
            return;
        }

        try {
            JCTree.JCMethodDecl metricee = (JCTree.JCMethodDecl)method.get();
            RecordSize recordSize = annotation.getInstance();
            switch (recordSize.exceptionMode()) {
                case INCLUDE:
                    include(metricee, recordSize, annotationNode);
                    break;
                case EXCLUDE:
                    exclude(metricee, recordSize, annotationNode);
                    break;
                case ONLY:
                    only(metricee, recordSize, annotationNode);
                    break;
                default:
            }
        } catch (CheckException e) {

        }

    }

    private void only(JCTree.JCMethodDecl methodDecl, RecordSize recordSize, JavacNode node) {
        JavacTreeMaker maker = node.getTreeMaker();

        Name resultName = resultName(node);

        JCTree.JCMethodDecl realMethod = createAndInjectRealMethod(node, methodDecl, REAL_METHOD_SUFFIX);
        JCTree.JCStatement callStat = callRealMethod(node, realMethod, resultName);
        JCTree.JCBlock tryBlock = maker.Block(0, List.of(callStat));

        long exceptionSize = recordSize.exceptionSize();
        JCTree.JCStatement recordStat = recordStat(node, recordSize, longLiteral(maker, exceptionSize));

        List<JCTree.JCExpression> exceptions = findExceptions(node);
        List<JCTree.JCCatch> catchers;
        if (exceptions.size() == 0) {
            catchers = List.of(catchThrowable(node, recordStat));
        } else {
            catchers = catchBlocks(node, recordStat, exceptions);
        }

        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        appendResultDecl(statements, node, resultName, realMethod);
        statements.append(maker.Try(tryBlock, catchers, null));
        appendReturnStat(statements, node, resultName, realMethod);
        methodDecl.body = maker.Block(0, statements.toList());
    }

    private void exclude(JCTree.JCMethodDecl methodDecl, RecordSize recordSize, JavacNode node) {
        JavacTreeMaker maker = node.getTreeMaker();

        Name resultName = resultName(node);

        JCTree.JCMethodDecl realMethod = createAndInjectRealMethod(node, methodDecl, REAL_METHOD_SUFFIX);
        JCTree.JCStatement callStat = callRealMethod(node, realMethod, resultName);

        List<JCTree.JCExpression> exceptions = findExceptions(node);
        JCTree.JCStatement invokeStat;
        if (exceptions.size() == 0) {
            invokeStat = callStat;
        } else {
            long exceptionSize = recordSize.exceptionSize();
            List<JCTree.JCCatch> catchers = new ListBuffer<JCTree.JCCatch>()
                    .appendList(catchBlocks(node, null, exceptions))
                    .append(catchThrowable(node, recordStat(node, recordSize, longLiteral(maker, exceptionSize))))
                    .toList();
            JCTree.JCBlock tryBlock = maker.Block(0, List.of(callStat));
            invokeStat = maker.Try(tryBlock, catchers, null);
        }

        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        appendResultDecl(statements, node, resultName, realMethod);
        statements.append(invokeStat);
        statements.append(normalRecord(node, resultName, realMethod, recordSize));
        appendReturnStat(statements, node, resultName, realMethod);
        methodDecl.body = maker.Block(0, statements.toList());
    }

    private void include(JCTree.JCMethodDecl methodDecl, RecordSize recordSize, JavacNode node) {
        JavacTreeMaker maker = node.getTreeMaker();

        Name resultName = resultName(node);

        JCTree.JCMethodDecl realMethod = createAndInjectRealMethod(node, methodDecl, REAL_METHOD_SUFFIX);
        JCTree.JCStatement callRealMethod = callRealMethod(node, realMethod, resultName);
        JCTree.JCBlock tryBlock = maker.Block(0, List.of(callRealMethod));

        long exceptionSize = recordSize.exceptionSize();
        JCTree.JCStatement recordExceptionStat = recordStat(node, recordSize, longLiteral(maker, exceptionSize));

        List<JCTree.JCExpression> exceptions = findExceptions(node);
        List<JCTree.JCCatch> catchers;
        if (exceptions.size() == 0) {
            catchers = List.of(catchThrowable(node, recordExceptionStat));
        } else {
            catchers = catchBlocks(node, recordExceptionStat, exceptions);
        }

        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        appendResultDecl(statements, node, resultName, realMethod);
        statements.append(maker.Try(tryBlock, catchers, null));
        statements.append(normalRecord(node, resultName, realMethod, recordSize));
        appendReturnStat(statements, node, resultName, realMethod);
        methodDecl.body = maker.Block(0, statements.toList());
    }

    private JCTree.JCStatement normalRecord(JavacNode node, Name resultName, JCTree.JCMethodDecl realMethod,
            RecordSize recordSize) {
        JavacTreeMaker maker = node.getTreeMaker();

        boolean byteShortIntLong = isByteShortIntLong(realMethod.restype);
        if (byteShortIntLong) {
            JCTree.JCExpression sizeExpression = maker.Ident(resultName);
            return createIgnoreZeroStatement(node, recordSize, sizeExpression);
        } else {
            JCTree.JCBinary nullCondition = maker.Binary(JavacTreeMaker.TreeTag.treeTag("NE"),
                    maker.Ident(resultName),
                    maker.Literal(JavacTreeMaker.TypeTag.typeTag("BOT"), null));

            JCTree.JCExpression sizeExpression = sizeExpression(node, resultName, realMethod, false);
            JCTree.JCStatement thenStat = createIgnoreZeroStatement(node, recordSize, sizeExpression);

            JCTree.JCStatement elseStat = recordSize.ignoreNull() ? null
                    : recordStat(node, recordSize, longLiteral(maker, recordSize.nullSize()));

            return maker.If(nullCondition, thenStat, elseStat);
        }
    }

    private JCTree.JCStatement createIgnoreZeroStatement(JavacNode node, RecordSize recordSize,
            JCTree.JCExpression sizeExpression) {
        JavacTreeMaker maker = node.getTreeMaker();

        if (recordSize.ignoreZero()) {
            Name sizeName = node.toName("__$size__");
            JCTree.JCVariableDecl sizeVarDef = maker.VarDef(maker.Modifiers(0), sizeName,
                    maker.TypeIdent(Javac.CTC_LONG), sizeExpression);

            JCTree.JCBinary zeroCondition = maker.Binary(JavacTreeMaker.TreeTag.treeTag("NE"),
                    maker.Ident(sizeName), longLiteral(maker, 0));
            JCTree.JCStatement zeroElseStat = recordStat(node, recordSize, maker.Ident(sizeName));
            JCTree.JCIf ifStat = maker.If(zeroCondition, zeroElseStat, null);

            return maker.Block(0, List.of(sizeVarDef, ifStat));
        } else {
            return recordStat(node, recordSize, sizeExpression);
        }
    }

    private JCTree.JCStatement recordStat(JavacNode node, RecordSize recordSize, JCTree.JCExpression sizeExpression) {
        JavacTreeMaker maker = node.getTreeMaker();

        JCTree.JCExpression metrics = maker.Ident(node.toName("Metrics"));
        metrics = addFixTags(node, metrics, recordSize.fixTags());
        metrics = addVarTags(node, metrics, recordSize.tags());

        ListBuffer<JCTree.JCExpression> args = new ListBuffer<JCTree.JCExpression>();
        args.append(maker.Literal(recordSize.name()));
        args.append(sizeExpression);

        return maker.Exec(maker.Apply(List.<JCTree.JCExpression>nil(),
                maker.Select(metrics, node.toName("recordSize")),
                args.toList()));
    }

    private JCTree.JCExpression sizeExpression(JavacNode node, Name resultName, JCTree.JCMethodDecl method,
            boolean byteShortIntLong) {
        JavacTreeMaker maker = node.getTreeMaker();

        Type type = method.restype.type;
        JCTree.JCIdent result = maker.Ident(resultName);
        List<JCTree.JCExpression> nil = List.<JCTree.JCExpression>nil();
        if (byteShortIntLong) {
            return maker.Ident(resultName);
        } else if (isSubClassOf(type, Number.class)) {
            JCTree.JCFieldAccess longValue = maker.Select(result, node.toName("longValue"));
            return maker.Apply(nil, longValue, nil);
        } else if (type instanceof Type.ArrayType) {
            return maker.Select(result, node.toName("length"));
        } else if (isSubClassOf(type, Collection.class)) {
            JCTree.JCFieldAccess size = maker.Select(result, node.toName("size"));
            return maker.Apply(nil, size, nil);
        } else {
            node.addError(
                    "@RecordSize only support method with return type of [Number,Array,Collection,byte,short,int,long], but is "
                            + type.toString());
            throw new CheckException();
        }
    }

    private boolean isByteShortIntLong(JCTree.JCExpression resType) {
        if (!(resType instanceof JCTree.JCPrimitiveTypeTree)) {
            return false;
        }
        TypeKind typeKind = ((JCTree.JCPrimitiveTypeTree)resType).getPrimitiveTypeKind();
        switch (typeKind) {
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
                return true;
            default:
                return false;
        }
    }
}
