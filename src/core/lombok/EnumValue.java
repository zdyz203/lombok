/*
 * Copyright (C) 2009-2017 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put on [byte,char,short,int] field to make lombok build one getter and one setter of enum type.
 * The enum class should declare tow method: static method [of] and instance method [getValue].
 *
 * <p>
 * Example:
 * <pre>
 *     private &#64;EnumValue(Bound.class) int foo;
 * </pre>
 * 
 * will generate:
 * 
 * <pre>
 *     public Bound getFoo() {
 *         return Bound.of(foo);
 *     }
 *     public void setFoo(Bound foo) {
 *         this.foo = foo.getValue();
 *     }
 * </pre>
 * If you want your getter and setter to be non-public,
 * you can specify an alternate access level using @Getter or @Setter with specified access level on method or class
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface EnumValue {
	/**
	 * @return the enum class of associated field;
	 */
	Class<? extends Enum> value();
}
