package lombok;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * FixTag可以放在注解上
 *      例如：
 *      @RecordOne(tags = @MetricTag(key="channel", value = "mobile"))
 *      public void search(){
 *          // do something
 *      }
 * 这是一个固定的tag(channel总是为mobile)，每次记录都会打上这个固定的tag
 * @author luoml
 * @date 2019/9/23
 */

@Retention(RetentionPolicy.SOURCE)
@Documented
public @interface FixTag {
    String name();

    String value();
}
