/*
 * Copyright (C) 2009-2017 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.mangosdk.spi.ProviderFor;

import lombok.AccessLevel;
import lombok.Coded;
import lombok.core.AnnotationValues;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

/**
 * Handles the {@code lombok.Coded} annotation for javac.
 */
@ProviderFor(EclipseAnnotationHandler.class) public class HandleCode extends EclipseAnnotationHandler<Coded> {
	
	@Override public void handle(AnnotationValues<Coded> annotation, Annotation ast, EclipseNode annotationNode) {
		
		EclipseNode typeNode = annotationNode.up();
		
		// Careful: Generate the public static constructor (if there is one)
		// LAST, so that any attempt to
		// 'find callers' on the annotation node will find callers of the
		// constructor, which is by far the
		// most useful of the many methods built by @Data. This trick won't work
		// for the non-static constructor,
		// for whatever reason, though you can find callers of that one by
		// focusing on the class name itself
		// and hitting 'find callers'.
		
		new HandleGetter().generateDecodeGetterForType(typeNode, annotationNode, AccessLevel.PUBLIC, true);
		new HandleSetter().generateEncodeSetterForType(typeNode, annotationNode, AccessLevel.PUBLIC, true);
	}
	
}
