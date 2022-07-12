package lombok;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.util.Collection;

/**
 * 放在方法或者方法参数上 方法的返回值类型和参数类型需要为
 *  {@link Collection}
 * 或者 {@link Array}
 * 或者 {@link Number}
 * 或者 byte/short/int/long
 * 
 * @author luoml
 * @date 2019/9/23
 */

@Target({ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface RecordSize {

    /**
     * metric的name
     *      用于Metrics.recordSize(name)
     */
    String name();

    /**
     * 只指定了name的tag，value从参数列表中获取
     *     用于withTag(tag.name, tag.valueName)
     */
    VarTag[] tags() default {};

    /**
     * 同时指定了name和value的tag
     *     用于withTag(tag.name, tag.value)
     */
    FixTag[] fixTags() default {};

    /**
     * 方法抛出异常时，记录metric的方式
     */
    ExceptionMode exceptionMode() default ExceptionMode.INCLUDE;

    /**
     * 配合exceptionMode使用，用于限制exceptionMode的语义。
     */
    Class<? extends Throwable>[] exceptions() default {};

    /**
     * 异常的size
     * 用法为：recordSize(name, exceptionSize);
     */
    long exceptionSize() default 0;

    /**
     * 目标 size() == 0 或者 length == 0 或者 longValue()为 0 时，是否不记录metric
     * 对异常的 exceptionSize == 0 无效(符合条件的异常的exceptionSize总会被记录)
     * 对 nullSize == 0 无效
     */
    boolean ignoreZero() default false;

    /**
     * 目标为null时，是否不记录metric
     */
    boolean ignoreNull() default true;

    /**
     * 目标null时，记录的metric的size
     * 用法为：recordSize("xxx", nullSize);
     * 需要 {@link RecordSize#ignoreNull()} == false
     */
    long nullSize() default 0;

}
