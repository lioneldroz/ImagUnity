package com.imagunity.api;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Fournisseurs de beans facultatifs pour les tests. */
final class Providers {

    private Providers() {
    }

    static <T> ObjectProvider<T> of(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("bean", bean)).getBeanProvider(type);
    }

    static <T> ObjectProvider<T> none(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }
}
