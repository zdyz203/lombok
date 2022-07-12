package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Collection<String> getStringXXX() {
 *    List<String> list = new ArrayList<>();
 *    for (int i : col) {
 *        list.add(CodeUtil.decode(i));
 *    }
 *    return list;
 * }
 *
 * @author jun.zhanga
 * @since 2019/12/26
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface CodedCollection {
}
