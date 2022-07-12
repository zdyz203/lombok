package lombok;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.util.Collection;

/**
 * 放在字段上或者方法(方法不能有参数)上。
 *
 * 字段类型或者方法的返回类型需为
 *  {@link Collection}  使用coll.size()
 *  {@link Array}       使用array.length
 *  {@link Number}      使用number.doubleValue()
 *  byte/short/int/long/float/double 使用它本身的值
 * 
 * @author luoml
 * @date 2019/9/23
 */

@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface Gauge {

    /**
     * metric的name
     *      用于Metrics.addGauge(name)
     */
    String name();

    /**
     * metric的tag
     *      用于withTag(tag.key, tag.value)
     */
    FixTag[] fixTags() default {};

    /**
     * 目标为null时，记录的metric的value
     */
    double nullValue() default 0;
}
