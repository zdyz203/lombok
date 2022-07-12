package lombok;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @author luoml
 * @date 2019/9/23
 */

@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface VarTag {
    /**
     * 指定metric的name
     */
    String name();

    /**
     * 指定metric的value的变量名
     * 可不填，如果不填，使用name作为变量名
     */
    String valueName() default "";

}
