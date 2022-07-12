package lombok.javac.handlers;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import lombok.CodedCollection;
import lombok.core.AST;
import lombok.core.AnnotationValues;
import lombok.core.handlers.HandlerUtil;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;
import org.mangosdk.spi.ProviderFor;

import java.util.Collection;

import static lombok.javac.Javac.CTC_LONG;
import static lombok.javac.handlers.JavacHandlerUtil.*;

/**
 * @author jun.zhanga
 * @since 2019/12/26
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleCodedCollection extends JavacAnnotationHandler<CodedCollection> {
    @Override
    public void handle(AnnotationValues<CodedCollection> annotation, JCTree.JCAnnotation ast, JavacNode annotationNode) {
        Collection<JavacNode> fields = annotationNode.upFromAnnotationToFields();
        JavacNode node = annotationNode.up();
        if (node == null) {
            return;
        }
        createCodedCollectionForFields(fields, annotationNode);
    }

    private void createCodedCollectionForFields(Collection<JavacNode> fieldNodes, JavacNode errorNode) {
        for (JavacNode fieldNode : fieldNodes) {
            createCodedCollectionForField(fieldNode, errorNode);
        }
    }

    private void createCodedCollectionForField(JavacNode fieldNode, JavacNode source) {
        if (fieldNode.getKind() != AST.Kind.FIELD) {
            source.addError("@CodedCollection is only supported on a field");
            return;
        }

        Type type = JavacHandlerUtil.getMirrorForFieldType(fieldNode);
        if (type == null || !JavacHandlerUtil.isSubClassOf(type, Collection.class)) {
            source.addError("@CodedCollection is only supported on subclass of collection");
            return;
        }

        String methodName = HandlerUtil.buildAccessorName("getDecode", fieldNode.getName());
        switch (methodExists(methodName, fieldNode, false, 0)) {
            case EXISTS_BY_LOMBOK:
                return;
            case EXISTS_BY_USER:
                source.addWarning(String.format("Not generating %s(): A method with that name already exists", methodName));
                return;
            case NOT_EXISTS:
            default:
                break;
        }
        JCTree.JCMethodDecl methodDecl = createMethod(methodName, fieldNode, fieldNode.getTreeMaker(), source.get());
        injectMethod(fieldNode.up(), methodDecl);
    }

    private JCTree.JCMethodDecl createMethod(String methodName, JavacNode field, JavacTreeMaker treeMaker, JCTree source) {
        JCTree.JCModifiers mods = treeMaker.Modifiers(Flags.PUBLIC);
        Name name = field.toName(methodName);
        JCTree.JCExpression returnType = generateCollectionStringTypeRef(field, treeMaker);
        List<JCTree.JCTypeParameter> typeParameters = List.<JCTree.JCTypeParameter>nil();
        List<JCTree.JCVariableDecl> parameters = List.<JCTree.JCVariableDecl>nil();
        List<JCTree.JCExpression> throwsClauses = List.<JCTree.JCExpression>nil();

        //Collection<String> getStringXXX() {
        //   List<String> list = new ArrayList<>();
        //   for (int i : col) {
        //       list.add(CodeUtil.decode(i));
        //   }
        //   return list;
        //}
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<JCTree.JCStatement>();
        Name resultName = field.toName("result");
        JCTree.JCExpression listType = generateListStringTypeRef(field, treeMaker);
        JCTree.JCExpression arrayList = generateArrayListStringTypeRef(field, treeMaker);
        JCTree.JCExpression init = treeMaker.NewClass(null, List.<JCTree.JCExpression>nil(), arrayList, List.<JCTree.JCExpression>nil(), null);
        statements.append(treeMaker.VarDef(treeMaker.Modifiers(0), resultName, listType, init));

        JCTree.JCExpression forEachType = treeMaker.TypeIdent(CTC_LONG);
        Name item = field.toName("item");
        Name decodeName = field.toName("decode");
        JCTree.JCExpression codeUtil = chainDotsString(field, "com.ctrip.flight.intl.common.utils.CodeUtil");
        JCTree.JCExpression decode = treeMaker.Apply(List.<JCTree.JCExpression>nil(), treeMaker.Select(codeUtil, decodeName), List.<JCTree.JCExpression>of(treeMaker.Ident(item)));
        JCTree.JCExpression add = treeMaker.Select(treeMaker.Ident(resultName), field.toName("add"));
        JCTree.JCStatement execute = treeMaker.Exec(treeMaker.Apply(List.<JCTree.JCExpression>nil(), add, List.<JCTree.JCExpression>of(decode)));
        JCTree.JCBlock forEachBody = treeMaker.Block(0, List.<JCTree.JCStatement>of(execute));
        JCTree.JCVariableDecl forEachVal = treeMaker.VarDef(treeMaker.Modifiers(0), item, forEachType, null);
        JCTree.JCExpression iterable = treeMaker.Ident(field.toName(field.getName()));
        JCTree.JCStatement forEach = treeMaker.ForeachLoop(forEachVal, iterable, forEachBody);

        statements.append(forEach);
        statements.append(treeMaker.Return(treeMaker.Ident(resultName)));

        JCTree.JCBlock methodBody = treeMaker.Block(0, statements.toList());
        JCTree.JCMethodDecl methodDecl = treeMaker.MethodDef(mods, name, returnType, typeParameters, parameters, throwsClauses, methodBody, null);
        return recursiveSetGeneratedBy(methodDecl, source, field.getContext());
    }

    private JCTree.JCExpression generateCollectionStringTypeRef(JavacNode field, JavacTreeMaker treeMaker) {
        JCTree.JCExpression type = chainDotsString(field, "java.util.Collection");
        return treeMaker.TypeApply(type, List.of(genJavaLangTypeRef(field, "String")));
    }

    private JCTree.JCExpression generateListStringTypeRef(JavacNode field, JavacTreeMaker treeMaker) {
        JCTree.JCExpression type = chainDotsString(field, "java.util.List");
        return treeMaker.TypeApply(type, List.of(genJavaLangTypeRef(field, "String")));
    }

    private JCTree.JCExpression generateArrayListStringTypeRef(JavacNode field, JavacTreeMaker treeMaker) {
        JCTree.JCExpression type = chainDots(field, "java", "util", "ArrayList");
        return treeMaker.TypeApply(type, List.of(genJavaLangTypeRef(field, "String")));
    }
}
