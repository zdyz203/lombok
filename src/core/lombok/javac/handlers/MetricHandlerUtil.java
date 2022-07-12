package lombok.javac.handlers;

import static lombok.javac.handlers.JavacHandlerUtil.addImport;
import static lombok.javac.handlers.JavacHandlerUtil.recursiveSetGeneratedBy;

import java.lang.annotation.Annotation;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

import lombok.FixTag;
import lombok.VarTag;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

/**
 * @author luoml
 * @date 2019/10/4
 */
public class MetricHandlerUtil {

    private MetricHandlerUtil() {
        // Prevent instantiation
    }

    public static void addMetricImport(JavacNode node) {
        String packageName = "com.ctrip.flight.intl.common.metric";
        String className = "Metrics";
        addImport(node, packageName, className);
    }

    public static JCTree.JCMethodDecl createAndInjectRealMethod(JavacNode node, JCTree.JCMethodDecl oldMethod,
            String suffix) {
        JavacTreeMaker maker = node.getTreeMaker();
        JCTree.JCMethodDecl methodDecl = maker.MethodDef(maker.Modifiers(oldMethod.mods.flags),
                node.toName(oldMethod.name.toString() + "___$real_" + suffix),
                oldMethod.restype, oldMethod.typarams, oldMethod.params, oldMethod.thrown, oldMethod.body,
                oldMethod.defaultValue);
        JavacHandlerUtil.injectMethod(JavacHandlerUtil.upToTypeNode(node), methodDecl);
        return methodDecl;
    }

    public static void removeAnnotationIfExist(JavacNode method, Class<? extends Annotation> annotationType) {
        JavacNode annotation = JavacHandlerUtil.findAnnotation(annotationType, method, false);
        if (annotation == null) {
            return;
        }
        JavacHandlerUtil.doDeleteAnnotation(annotation, annotationType);
    }

    public static Name resultName(JavacNode node) {
        return node.toName("__$result__");
    }

    public static JCTree.JCExpressionStatement callRealMethod(JavacNode node, JCTree.JCMethodDecl realMethod,
            Name resultName) {
        JavacTreeMaker maker = node.getTreeMaker();
        JCTree.JCExpression[] args = new JCTree.JCExpression[realMethod.params.size()];
        List<JCTree.JCVariableDecl> params = realMethod.params;
        for (int i = 0; i < params.size(); i++) {
            JCTree.JCVariableDecl param = params.get(i);
            args[i] = maker.Ident(param.name);
        }
        JCTree.JCMethodInvocation apply = maker.Apply(List.<JCTree.JCExpression>nil(),
                maker.Select(maker.Ident(node.toName("this")), node.toName(realMethod.name.toString())),
                List.from(args));
        if (returnVoid(realMethod)) {
            return maker.Exec(apply);
        } else {
            return maker.Exec(maker.Assign(maker.Ident(resultName), apply));
        }
    }

    public static boolean returnVoid(JCTree.JCMethodDecl realMethod) {
        return "void".equalsIgnoreCase(realMethod.restype.type.toString());
    }

    public static JCTree.JCExpression addFixTags(JavacNode node, JCTree.JCExpression calls, FixTag[] tags) {
        JavacTreeMaker maker = node.getTreeMaker();
        for (FixTag tag : tags) {
            JCTree.JCLiteral tagName = maker.Literal(tag.name());
            JCTree.JCLiteral tagValue = maker.Literal(tag.value());
            calls = maker.Apply(List.<JCTree.JCExpression>nil(),
                    maker.Select(calls, node.toName("withTag")),
                    List.<JCTree.JCExpression>of(tagName, tagValue));
        }
        return calls;
    }

    public static JCTree.JCExpression addVarTags(JavacNode node, JCTree.JCExpression calls, VarTag[] tags) {
        JavacTreeMaker maker = node.getTreeMaker();
        for (VarTag tag : tags) {
            JCTree.JCLiteral tagName = maker.Literal(tag.name());
            String valueName = tag.valueName().isEmpty() ? tag.name() : tag.valueName();
            JCTree.JCExpression tagValue = JavacHandlerUtil.chainDotsString(node, valueName);
            calls = maker.Apply(List.<JCTree.JCExpression>nil(), maker.Select(calls, node.toName("withTag")),
                    List.of(tagName, tagValue));
        }
        return calls;
    }

    public static List<JCTree.JCExpression> findExceptions(JavacNode annotationNode) {
        JCTree.JCAnnotation jcTree = (JCTree.JCAnnotation)annotationNode.get();
        JCTree.JCNewArray exceptions = null;
        for (JCTree.JCExpression argument : jcTree.getArguments()) {
            JCTree.JCAssign arg = (JCTree.JCAssign)argument;
            if ("exceptions".equalsIgnoreCase(arg.getVariable().toString())) {
                exceptions = (JCTree.JCNewArray)arg.getExpression();
                break;
            }
        }
        if (exceptions == null) {
            return List.nil();
        }
        JavacTreeMaker maker = annotationNode.getTreeMaker();
        ListBuffer<JCTree.JCExpression> buffer = new ListBuffer<JCTree.JCExpression>();
        for (JCTree.JCExpression initializer : exceptions.getInitializers()) {
            JCTree.JCIdent selected = (JCTree.JCIdent)((JCTree.JCFieldAccess)initializer).selected;
            buffer.append(maker.Ident(annotationNode.toName(selected.name.toString())));
        }

        return buffer.toList();
    }

    public static void appendResultDecl(ListBuffer<JCTree.JCStatement> statements,
            JavacNode node, Name name, JCTree.JCMethodDecl realMethod) {
        if (returnVoid(realMethod)) {
            return;
        }
        JavacTreeMaker maker = node.getTreeMaker();
        JCTree.JCVariableDecl varDef = maker.VarDef(maker.Modifiers(0), name, realMethod.restype, null);
        statements.append(varDef);
    }

    public static void appendReturnStat(ListBuffer<JCTree.JCStatement> statements,
            JavacNode node, Name name, JCTree.JCMethodDecl realMethod) {
        if (returnVoid(realMethod)) {
            return;
        }
        JavacTreeMaker maker = node.getTreeMaker();
        JCTree.JCReturn returnStat = maker.Return(maker.Ident(name));
        statements.append(returnStat);
    }

    public static JCTree.JCCatch catchThrowable(JavacNode node, JCTree.JCStatement recordStats) {
        JavacTreeMaker maker = node.getTreeMaker();
        return catchBlock(node, recordStats, maker.Ident(node.toName("Throwable")));
    }

    public static List<JCTree.JCCatch> catchBlocks(JavacNode node, JCTree.JCStatement recordStats,
            List<JCTree.JCExpression> exceptions) {
        ListBuffer<JCTree.JCCatch> buffer = new ListBuffer<JCTree.JCCatch>();
        for (JCTree.JCExpression exception : exceptions) {
            buffer.append(catchBlock(node, recordStats, exception));
        }
        return buffer.toList();
    }

    public static JCTree.JCCatch catchBlock(JavacNode node, JCTree.JCStatement recordStats,
            JCTree.JCExpression exception) {
        JavacTreeMaker maker = node.getTreeMaker();
        Name name = node.toName("$ex");
        JCTree.JCVariableDecl var = maker.VarDef(maker.Modifiers(0), name, exception, null);

        ListBuffer<JCTree.JCStatement> buffer = new ListBuffer<JCTree.JCStatement>();
        if (recordStats != null) {
            buffer.append(recordStats);
        }
        buffer.append(maker.Throw(maker.Ident(name)));
        JCTree.JCBlock block = maker.Block(0, buffer.toList());

        return recursiveSetGeneratedBy(maker.Catch(var, block), node.get(), node.getContext());
    }
}
