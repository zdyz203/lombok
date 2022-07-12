package lombok;

/**
 * 方法抛出异常时，记录metric的方式
 * 
 * @author luoml
 * @date 2019/9/23
 */
public enum ExceptionMode {
    /**
     * 总是记录正常调用
     *
     * 如果exceptions为空，记录所有异常
     * 如果exceptions不为空，记录exceptions指定的异常
     */
    INCLUDE,

    /**
     * 总是记录正常调用
     *
     * 如果exceptions为空，不记录异常
     * 如果exceptions不为空，记录异常，但不记录exceptions指定的异常
     */
    EXCLUDE,

    /**
     * 不记录正常调用
     * 
     * 如果exceptions为空，记录所有异常
     * 如果exceptions不为空，只记录exceptions指定的异常
     */
    ONLY,

}