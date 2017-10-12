/*
 * Copyright 2009-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.eclipse.codeassist.proposals;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.eclipse.codeassist.relevance.Relevance;
import org.codehaus.groovy.eclipse.codeassist.requestor.ContentAssistContext;
import org.eclipse.jdt.groovy.core.util.GroovyUtils;
import org.eclipse.jdt.groovy.search.VariableScope;

public abstract class AbstractGroovyProposal implements IGroovyProposal {

    /**
     * @return AST node associated with this proposal, or {@code null} if there is none
     */
    public AnnotatedNode getAssociatedNode() {
        return null;
    }

    private float relevanceMultiplier = 1;

    public final float getRelevanceMultiplier() {
        return relevanceMultiplier;
    }

    public final void setRelevanceMultiplier(float relevanceMultiplier) {
        // TODO: Ensure multiplier is between 0.1 and 10 (exclusive)?
        this.relevanceMultiplier = relevanceMultiplier;
    }

    private String requiredStaticImport;

    public final String getRequiredStaticImport() {
        return requiredStaticImport;
    }

    public final void setRequiredStaticImport(String requiredStaticImport) {
        this.requiredStaticImport = requiredStaticImport;
    }

    //--------------------------------------------------------------------------

    protected int computeRelevance(ContentAssistContext context) {
        int relevance = getRelevanceClass().getRelevance(getRelevanceMultiplier());

        AnnotatedNode node = getAssociatedNode();
        if (node != null && context.lhsType != null) {
            ClassNode type = null;
            if (node instanceof FieldNode) {
                type = ((FieldNode) node).getType();
            } else if (node instanceof MethodNode) {
                type = ((MethodNode) node).getReturnType();
            } else if (node instanceof PropertyNode) {
                type = ((PropertyNode) node).getType();
            }
            if (type != null && GroovyUtils.isAssignable(type, context.lhsType)) {
                relevance = Math.max(relevance, Relevance.HIGH.getRelevance());
            }
        }

        if (this instanceof GroovyMethodProposal) {
            relevance *= 0.9f; // promote fields
        }

        return relevance;
    }

    private Relevance getRelevanceClass() {
        AnnotatedNode node = getAssociatedNode();

        if (this instanceof GroovyNamedArgumentProposal) {
            return Relevance.HIGH;
        }
        if (node != null && EXTREMELY_COMMON_TYPES.contains(node.getDeclaringClass())) {
            return Relevance.VERY_LOW;
        }
        if (node instanceof FieldNode || node instanceof MethodNode || node instanceof PropertyNode) {
            return Relevance.MEDIUM_HIGH;
        }
        return Relevance.MEDIUM;
    }

    private static final Set<ClassNode> EXTREMELY_COMMON_TYPES;
    static {
        Set<ClassNode> types =
            new HashSet<ClassNode>();
        types.add(VariableScope.CLASS_CLASS_NODE);
        types.add(VariableScope.OBJECT_CLASS_NODE);
        types.add(VariableScope.GROOVY_OBJECT_CLASS_NODE);
        EXTREMELY_COMMON_TYPES = Collections.unmodifiableSet(types);
    }
}
