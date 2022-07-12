package lombok;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.util.Collection;

/**
 * 暂时还没有实现这个注解!!!!
 * 
 * 放在方法或者方法参数上
 * 方法的返回值类型和参数类型需要为
 *        {@link Collection}
 * 或者   {@link Array}
 * 或者   {@link Number}
 *
 * @author luoml
 * @date 2019/9/23
 */

@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface RecordMany {

    /**
     * metric的name
     *      用于Metrics.recordMany(name, count)
     */
    String name() default "";

    /**
     * metric的tag
     *      用于withTag(tag.key, tag.value)
     */
    VarTag[] tags() default {};

    /**
     * 方法抛出异常时，记录metric的方式
     */
    ExceptionMode exceptionMode() default ExceptionMode.INCLUDE;

    /**
     * 配合exceptionMode使用，用于限制exceptionMode的语义。
     */
    Class<? extends Throwable>[] exceptions() default {};

    /**
     * 异常的count
     * 用法为：recordMany("xxx", exceptionCount);
     */
    int exceptionCount() default 0;

    /**
     * null的count
     * 用法为：recordMany("xxx", nullCount);
     */
    int nullCount() default 0;
}
