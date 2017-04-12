// Copyright (c) 2014-2016 K Team. All Rights Reserved.
package org.kframework.backend.java.util;

import com.google.common.collect.ArrayTable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import org.kframework.Collections;
import org.kframework.backend.java.MiniKoreUtils;
import org.kframework.backend.java.kil.Sort;
import org.kframework.definition.Module;
import org.kframework.kil.loader.Context;
import org.kframework.minikore.implementation.MiniKore;
import org.kframework.utils.errorsystem.KEMException;
import scala.collection.JavaConversions;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Subsort relation.
 *
 * TODO(YilongL): delegates this to KORE/Context
 *
 * @author YilongL
 */
public class Subsorts implements Serializable {

    private final Set<Sort> sorts;

    /**
     * {@code subsort[sort1][sort2] = true} iff {@code sort1} is bigger than
     * {@code sort2}.
     */
    private final Table<Sort, Sort, Boolean> subsort;

    public Subsorts(Context context) {
        Set<org.kframework.kil.Sort> genericKILSorts = context.getAllSorts();
        ImmutableSet.Builder<Sort> setBuilder = ImmutableSet.builder();
        for (org.kframework.kil.Sort genericKILSort : genericKILSorts) {
            /* ensure all sorts in context have Java-backend counterparts */
            Sort sort = Sort.of(genericKILSort.getName());
            setBuilder.add(sort);
        }
        this.sorts = setBuilder.build();

        this.subsort = ArrayTable.create(sorts, sorts);
        for (Sort sort1 : sorts) {
            for (Sort sort2 : sorts) {
                subsort.put(sort1, sort2, context
                        .isSubsorted(sort1.toFrontEnd(), sort2.toFrontEnd()));
            }
        }
    }

    // Minikore to Subsorts Constructor
    public Subsorts(MiniKoreUtils.ModuleUtils moduleUtils) {
        sorts = JavaConversions.asJavaCollection(moduleUtils.definedSorts()).stream()
                        .map(s -> Sort.of(s))
                        .collect(Collectors.toSet());

        this.subsort = ArrayTable.create(sorts, sorts);
        for (String sort1 : Collections.iterable(moduleUtils.definedSorts())) {
            for (String sort2 : Collections.iterable(moduleUtils.definedSorts())) {
                subsort.put(
                        Sort.of(sort1),
                        Sort.of(sort2),
                        moduleUtils.subsorts().$greater(sort1, sort2));
            }
        }
    }

    public Subsorts(Module module) {
        sorts = JavaConversions.asJavaCollection(module.definedSorts()).stream()
                .map(s -> Sort.of(s.name()))
                .collect(Collectors.toSet());

        this.subsort = ArrayTable.create(sorts, sorts);
        for (org.kframework.kore.Sort sort1 : Collections.iterable(module.definedSorts())) {
            for (org.kframework.kore.Sort sort2 : Collections.iterable(module.definedSorts())) {
                subsort.put(
                        Sort.of(sort1.name()),
                        Sort.of(sort2.name()),
                        module.subsorts().$greater(sort1, sort2));
            }
        }
    }

    public Set<Sort> allSorts() {
        return sorts;
    }

    public boolean isSubsorted(Sort bigSort, Sort smallSort) {
        Boolean isSubsorted = subsort.get(bigSort, smallSort);
        if (isSubsorted == null) {
            if (subsort.containsRow(bigSort)) {
                throw KEMException.criticalError("Sort " + smallSort.toString() + " is undefined.");
            } else {
                throw KEMException.criticalError("Sort " + bigSort.toString() + " is undefined.");
            }
        }
        return isSubsorted;
    }

    public boolean isSubsortedEq(Sort bigSort, Sort smallSort) {
        return bigSort == smallSort || isSubsorted(bigSort, smallSort);
    }

    public Sort getLUBSort(Sort... sorts) {
        return getLUBSort(Sets.newHashSet(sorts));
    }

    public Sort getLUBSort(Set<Sort> subset) {
        return getTopSort(subset, false);
    }

    public Set<Sort> getUpperBounds(Sort... sorts) {
        return getUpperBounds(Sets.newHashSet(sorts));
    }

    public Set<Sort> getUpperBounds(Set<Sort> subset) {
        return getBounds(subset, false);
    }

    public Sort getGLBSort(Sort... sorts) {
        return getGLBSort(Sets.newHashSet(sorts));
    }

    public Sort getGLBSort(Set<Sort> subset) {
        return getTopSort(subset, true);
    }

    public Set<Sort> getLowerBounds(Sort... sorts) {
        return getLowerBounds(Sets.newHashSet(sorts));
    }

    public Set<Sort> getLowerBounds(Set<Sort> subset) {
        return getBounds(subset, true);
    }

    public boolean hasCommonSubsort(Sort sort1, Sort sort2) {
        Set<Sort> lowerBounds = getLowerBounds(sort1, sort2);
        return !lowerBounds.isEmpty() &&
                !(lowerBounds.size() == 1 && lowerBounds.iterator().next().equals(Sort.BOTTOM));
    }

    private boolean inRelation(Sort sort1, Sort sort2, boolean direction) {
        return direction ? isSubsorted(sort2, sort1) : isSubsorted(sort1, sort2);
    }

    private Set<Sort> getBounds(Set<Sort> subset, boolean direction) {
        if (subset == null || subset.size() == 0) {
            return java.util.Collections.emptySet();
        }
        if (subset.size() == 1) {
            return java.util.Collections.singleton(subset.iterator().next());
        }

        Set<Sort> bounds = new HashSet<>();
        for (Sort candidate : sorts) {
            boolean isBound = true;
            for (Sort sort : subset) {
                if (!(inRelation(candidate, sort, direction) || candidate.equals(sort))) {
                    isBound = false;
                    break;
                }
            }
            if (isBound) {
                bounds.add(candidate);
            }
        }

        return bounds;
    }

    public Sort getTopSort(Set<Sort> subset, boolean direction) {
        if (subset == null || subset.size() == 0) {
            return null;
        }
        if (subset.size() == 1) {
            return subset.iterator().next();
        }

        Set<Sort> bounds = getBounds(subset, direction);
        if (bounds.size() == 0) {
            return null;
        }

        Sort candidate = null;
        for (Sort bound : bounds) {
            if (candidate == null) {
                candidate = bound;
            } else {
                if (inRelation(candidate, bound, direction)) {
                    candidate = bound;
                } else if (!inRelation(bound, candidate, direction)) {
                    /* no relation between bound and candidate; none of them is the top element */
                    candidate = null;
                }
            }
        }
        /* if there is a top element, it must be candidate */
        if (candidate != null) {
            for (Sort bound : bounds) {
                if (bound != candidate && !inRelation(bound, candidate, direction)) {
                    candidate = null;
                    break;
                }
            }
        }
        return candidate;
    }

}
