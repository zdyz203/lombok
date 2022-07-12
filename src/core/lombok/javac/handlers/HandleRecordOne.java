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

import java.util.concurrent.TimeUnit;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.RecordOne;
import lombok.core.AnnotationValues;
import lombok.javac.Javac;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

/**
 * Handles the {@code lombok.Coded} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleRecordOne extends JavacAnnotationHandler<RecordOne> {

    private static final String REAL_METHOD_SUFFIX = "one";

    @Override
    public void handle(AnnotationValues<RecordOne> annotation, JCAnnotation ast, JavacNode annotationNode) {
        addMetricImport(annotationNode);

        deleteAnnotationIfNeccessary(annotationNode, RecordOne.class);

        JavacNode method = annotationNode.up();

        if (!(method.get() instanceof JCTree.JCMethodDecl)) {
            return;
        }

        JCTree.JCMethodDecl metricee = (JCTree.JCMethodDecl)method.get();
        RecordOne recordOne = annotation.getInstance();
        switch (recordOne.exceptionMode()) {
            case INCLUDE:
                include(metricee, recordOne, annotationNode);
                break;
            case EXCLUDE:
                exclude(metricee, recordOne, annotationNode);
                break;
            case ONLY:
                only(metricee, recordOne, annotationNode);
                break;
            default:
        }

    }

    private void only(JCTree.JCMethodDecl methodDecl, RecordOne recordOne, JavacNode node) {
        JavacTreeMaker maker = node.getTreeMaker();

        Name resultName = resultName(node);
        JCTree.JCMethodDecl realMethod = createAndInjectRealMethod(node, methodDecl, REAL_METHOD_SUFFIX);
        JCTree.JCStatement callStat = callRealMethod(node, realMethod, resultName);
        JCTree.JCBlock tryBlock = maker.Block(0, List.of(callStat));
        JCTree.JCStatement recordStats = recordStats(node, recordOne);

        List<JCTree.JCExpression> exceptions = findExceptions(node);
        List<JCTree.JCCatch> catchers;
        if (exceptions.size() == 0) {
            catchers = List.of(catchThrowable(node, recordStats));
        } else {
            catchers = catchBlocks(node, recordStats, exceptions);
        }

        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        statements.append(startTimeStats(node, maker, recordOne));
        appendResultDecl(statements, node, resultName, realMethod);
        statements.append(maker.Try(tryBlock, catchers, null));
        appendReturnStat(statements, node, resultName, realMethod);
        methodDecl.body = maker.Block(0, statements.toList());
    }

    private void exclude(JCTree.JCMethodDecl methodDecl, RecordOne recordOne, JavacNode node) {
        JavacTreeMaker maker = node.getTreeMaker();

        Name resultName = resultName(node);
        JCTree.JCMethodDecl realMethod = createAndInjectRealMethod(node, methodDecl, REAL_METHOD_SUFFIX);
        JCTree.JCStatement callStat = callRealMethod(node, realMethod, resultName);
        JCTree.JCStatement recordStats = recordStats(node, recordOne);

        List<JCTree.JCExpression> exceptions = findExceptions(node);
        JCTree.JCStatement invokeStat;
        if (exceptions.size() == 0) {
            invokeStat = callStat;
        } else {
            List<JCTree.JCCatch> catchers = new ListBuffer<JCTree.JCCatch>()
                    .appendList(catchBlocks(node, null, exceptions))
                    .append(catchThrowable(node, recordStats))
                    .toList();
            JCTree.JCBlock tryBlock = maker.Block(0, List.of(callStat));
            invokeStat = maker.Try(tryBlock, catchers, null);
        }
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        statements.append(startTimeStats(node, maker, recordOne));
        appendResultDecl(statements, node, resultName, realMethod);
        statements.append(invokeStat);
        statements.append(normalRecord(node, resultName, realMethod, recordStats, recordOne));
        appendReturnStat(statements, node, resultName, realMethod);
        methodDecl.body = maker.Block(0, statements.toList());
    }

    private void include(JCTree.JCMethodDecl methodDecl, RecordOne recordOne, JavacNode node) {
        JavacTreeMaker maker = node.getTreeMaker();

        Name resultName = resultName(node);

        JCTree.JCMethodDecl realMethod = createAndInjectRealMethod(node, methodDecl, REAL_METHOD_SUFFIX);
        JCTree.JCStatement callRealMethod = callRealMethod(node, realMethod, resultName);
        JCTree.JCBlock tryBlock = maker.Block(0, List.of(callRealMethod));
        JCTree.JCStatement recordStats = recordStats(node, recordOne);

        List<JCTree.JCExpression> exceptions = findExceptions(node);
        List<JCTree.JCCatch> catchers;
        if (exceptions.size() == 0) {
            catchers = List.of(catchThrowable(node, recordStats));
        } else {
            catchers = catchBlocks(node, recordStats, exceptions);
        }

        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        statements.append(startTimeStats(node, maker, recordOne));
        appendResultDecl(statements, node, resultName, realMethod);
        statements.append(maker.Try(tryBlock, catchers, null));
        statements.append(normalRecord(node, resultName, realMethod, recordStats, recordOne));
        appendReturnStat(statements, node, resultName, realMethod);
        methodDecl.body = maker.Block(0, statements.toList());
    }

    private JCTree.JCStatement normalRecord(JavacNode node, Name resultName, JCTree.JCMethodDecl realMethod,
            JCTree.JCStatement recordStat, RecordOne recordOne) {
        if (!recordOne.ignoreNull()) {
            return recordStat;
        }
        if (!(realMethod.restype.type instanceof Type.ClassType)) {
            return recordStat;
        }

        JavacTreeMaker maker = node.getTreeMaker();

        JCTree.JCBinary condition = maker.Binary(JavacTreeMaker.TreeTag.treeTag("NE"),
                maker.Ident(resultName),
                maker.Literal(JavacTreeMaker.TypeTag.typeTag("BOT"), null));
        return maker.If(condition, recordStat, null);
    }

    private JCTree.JCStatement recordStats(JavacNode node, RecordOne recordOne) {
        JavacTreeMaker maker = node.getTreeMaker();

        JCTree.JCExpression metrics = maker.Ident(node.toName("Metrics"));
        metrics = addFixTags(node, metrics, recordOne.fixTags());
        metrics = addVarTags(node, metrics, recordOne.tags());

        List<JCTree.JCExpression> args = List.<JCTree.JCExpression>of(maker.Literal(recordOne.name()));
        if (recordOne.recordTime()) {
            args = List.of(args.get(0), endTimeExpression(node, maker, recordOne));
        }

        return maker.Exec(maker.Apply(List.<JCTree.JCExpression>nil(),
                maker.Select(metrics, node.toName("recordOne")),
                args));
    }

    private JCTree.JCExpression endTimeExpression(JavacNode node, JavacTreeMaker maker, RecordOne recordOne) {

        JCTree.JCBinary endTime = maker.Binary(JavacTreeMaker.TreeTag.treeTag("MINUS"),
                currentTimeCall(node, maker, recordOne),
                maker.Ident(node.toName("__$startTime__")));

        TimeUnit timeUnit = recordOne.timeUnit();
        switch (timeUnit) {
            case NANOSECONDS:
            case MILLISECONDS:
                return endTime;
            case MICROSECONDS:
                return maker.Binary(JavacTreeMaker.TreeTag.treeTag("DIV"),
                        endTime,
                        maker.Literal(Javac.CTC_LONG, TimeUnit.NANOSECONDS.convert(1, recordOne.timeUnit())));
            case SECONDS:
            case MINUTES:
            case HOURS:
            case DAYS:
            default:
                return maker.Binary(JavacTreeMaker.TreeTag.treeTag("DIV"),
                        endTime,
                        maker.Literal(Javac.CTC_LONG, TimeUnit.MILLISECONDS.convert(1, recordOne.timeUnit())));
        }
    }

    private JCTree.JCStatement startTimeStats(JavacNode node, JavacTreeMaker maker, RecordOne recordOne) {
        return maker.VarDef(maker.Modifiers(0),
                node.toName("__$startTime__"), maker.TypeIdent(Javac.CTC_LONG),
                currentTimeCall(node, maker, recordOne));
    }

    private JCTree.JCExpression currentTimeCall(JavacNode node, JavacTreeMaker maker, RecordOne recordOne) {
        Name timeMethodName;
        if (recordOne.timeUnit() == TimeUnit.NANOSECONDS || recordOne.timeUnit() == TimeUnit.MICROSECONDS) {
            timeMethodName = node.toName("nanoTime");
        } else {
            timeMethodName = node.toName("currentTimeMillis");
        }

        JCTree.JCFieldAccess currentTime = maker.Select(maker.Ident(node.toName("System")), timeMethodName);
        return maker.Apply(List.<JCTree.JCExpression>nil(), currentTime, List.<JCTree.JCExpression>nil());
    }
}
