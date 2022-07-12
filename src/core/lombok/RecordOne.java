package lombok;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 放在方法上
 * 
 * @author luoml
 * @date 2019/9/23
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface RecordOne {

    /**
     * metric的name
     *      用于Metrics.recordOne(name)
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
     * 是否记录时间
     * 如果记录
     *      使用Metrics.recordOne(value, time);
     * 否则
     *      使用Metrics.recordOne(value)
     */
    boolean recordTime() default true;

    /**
     * 时间的单位
     */
    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

    /**
     * 方法抛出异常时，记录metric的方式
     */
    ExceptionMode exceptionMode() default ExceptionMode.INCLUDE;

    /**
     * 配合exceptionMode使用，用于限制exceptionMode的语义。
     */
    Class<? extends Throwable>[] exceptions() default {};

    /**
     * 正常返回的情况下，返回值为null时，是否不记录metric
     * 对ExceptionMode.ONLY无效
     */
    boolean ignoreNull() default false;

}
