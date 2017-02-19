/*
 * Copyright (c) pakoito 2017
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pacoworks.rxobservablediskcache2;

import android.content.Context;

import com.pacoworks.rxpaper2.RxPaperBook;

import java.util.Arrays;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.functions.Function;
import io.reactivex.functions.Predicate;


/**
 * Static methods to add disk caching behaviour to {@link Single} objects.
 * <p/>
 * Make sure to call {@link RxPaperBook#init(Context)} at least once beforehand to initialize the
 * underlying database.
 *
 * @param <V> type of the data to store
 * @param <P> type of the policy to store
 * @author pakoito
 */
public class RxObservableDiskCache<V, P> {
    private static final String POLICY_APPEND = "_policy";

    private final RxPaperBook book;

    private final Function<V, P> policyCreator;

    private final Predicate<P> policyValidator;

    RxObservableDiskCache(RxPaperBook book, Function<V, P> policyCreator,
            Predicate<P> policyValidator) {
        this.book = book;
        this.policyValidator = policyValidator;
        this.policyCreator = policyCreator;
    }

    /**
     * Creates a reusable {@link RxObservableDiskCache} for the same {@link RxPaperBook}, Policy and
     * Value types.
     *
     * @param book {@link RxPaperBook} storage book
     * @param policyCreator lazy method to construct a Policy object
     * @param policyValidator lazy method to validate a Policy object
     */
    public static <V, P> RxObservableDiskCache<V, P> create(
            RxPaperBook book, Function<V, P> policyCreator, Predicate<P> policyValidator) {
        return new RxObservableDiskCache<>(book, policyCreator, policyValidator);
    }

    /**
     * Transforms a {@link Single} into an {@link Observable} returning a disk cached version of the
     * latest Value seen for the same key followed by the {@link Single} result.
     * <p/>
     * The execution assures that the cached Value, if available, will be returned first. If no
     * Value is cached, or its Policy is not validated, then the current Value and Policy are
     * deleted silently and just the value of the {@link Single} is returned.
     * <p/>
     * This version uses a default {@link RxPaperBook}.
     *
     * @param single {@link Single} operation whose result is to be cached
     * @param key string value under where the values will be stored
     * @param policyCreator lazy method to construct a Policy object
     * @param policyValidator lazy method to validate a Policy object
     * @param <V> type of the data to store
     * @param <P> type of the policy to store
     * @return an {@link Observable} that will return a cached Value followed by the result of
     *         executing single
     */
    public static <V, P> Observable<Cached<V, P>> transform(
            Single<V> single, String key, Function<V, P> policyCreator, Predicate<P> policyValidator) {
        return transform(single, key, RxPaperBook.with(BuildConfig.APPLICATION_ID), policyCreator,
                policyValidator);
    }

    /**
     * Transforms a {@link Single} into an {@link Observable} returning a disk cached version of the
     * latest Value seen for the same key followed by the {@link Single} result.
     * <p/>
     * The execution assures that the cached Value, if available, will be returned first. If no
     * Value is cached, or its Policy is not validated, then the current Value and Policy are
     * deleted silently and just the value of the {@link Single} is returned.
     *
     * @param single {@link Single} operation whose result is to be cached
     * @param key string value under where the values will be stored
     * @param paperBook storage book
     * @param policyCreator lazy method to construct a Policy object
     * @param policyValidator lazy method to validate a Policy object
     * @param <V> type of the data to store
     * @param <P> type of the policy to store
     * @return an {@link Observable} that will return a cached Value followed by the result of
     *         executing single
     */
    public static <V, P> Observable<Cached<V, P>> transform(
            final Single<V> single, final String key, final RxPaperBook paperBook,
            final Function<V, P> policyCreator, final Predicate<P> policyValidator) {
        return Observable
                /* Errors require being delayed so the cached subscription is completed even if the remote one fails */
                .concatDelayError(Arrays.asList(
                        RxObservableDiskCache.<V, P>requestCachedValue(key, paperBook, policyValidator),
                        RxObservableDiskCache.<V, P>requestFreshValue(single, key, paperBook, policyCreator)));
    }

    private static <V, P> Observable<Cached<V, P>> requestCachedValue(
            final String key, final RxPaperBook cache, Predicate<P> policyValidator) {
        return cache
                .<P> read(composePolicyKey(key))
                .toObservable()
                .filter(policyValidator)
                .switchIfEmpty(
                        RxObservableDiskCache.<P> deleteValueAndPolicy(key, cache)
                                .doOnComplete(Logging.logCacheInvalid(key)))
                .flatMap(RxObservableDiskCache.<V, P> readValue(key, cache))
                .doOnNext(Logging.<V, P> logCacheHit(key))
                .doOnError(Logging.<V, P> logCacheMiss(key))
                .onErrorResumeNext(RxObservableDiskCache.<V, P> handleErrors(key, cache));
    }

    private static <P> Observable<P> deleteValueAndPolicy(String key, RxPaperBook cache) {
        return Completable.mergeDelayError(Arrays.asList(cache.delete(key), cache.delete(composePolicyKey(key))))
                .toObservable();
    }

    private static <V, P> Function<P, Observable<Cached<V, P>>> readValue(
            final String key, final RxPaperBook cache) {
        return new Function<P, Observable<Cached<V, P>>>() {
            @Override
            public Observable<Cached<V, P>> apply(final P policy) {
                return cache.<V> read(key)
                        .map(RxObservableDiskCache.<V, P> createDiskCached(policy))
                        .toObservable();
            }
        };
    }

    private static <V, P> Function<Throwable, Observable<Cached<V, P>>> handleErrors(
            final String key, final RxPaperBook cache) {
        return new Function<Throwable, Observable<Cached<V, P>>>() {
            @Override
            public Observable<Cached<V, P>> apply(final Throwable throwable) {
                return RxObservableDiskCache.<Cached<V, P>> deleteValueAndPolicy(key, cache)
                        .flatMap(
                                new Function<Cached<V, P>, Observable<Cached<V, P>>>() {
                                    @Override
                                    public Observable<Cached<V, P>> apply(
                                            Cached<V, P> valuePolicyCached) {
                                        return Observable.error(throwable);
                                    }
                                });
            }
        };
    }

    private static <V, P> Observable<Cached<V, P>> requestFreshValue(
            Single<V> single, String key, RxPaperBook cache, Function<V, P> policyCreator) {
        return single.toObservable()
                .map(createObservableCached(policyCreator))
                .flatMap(RxObservableDiskCache.<V, P> toStoreKeyAndValue(key, cache));
    }

    private static <V, P> Function<Cached<V, P>, Observable<Cached<V, P>>> toStoreKeyAndValue(
            final String key, final RxPaperBook cache) {
        return new Function<Cached<V, P>, Observable<Cached<V, P>>>() {
            @Override
            public Observable<Cached<V, P>> apply(final Cached<V, P> ktCached) {
                return Completable
                        .mergeDelayError(Arrays.asList(
                                cache.write(key, ktCached.value),
                                cache.write(composePolicyKey(key), ktCached.policy)))
                        .andThen(Observable.just(ktCached));
            }
        };
    }

    private static String composePolicyKey(String key) {
        return key + POLICY_APPEND;
    }

    private static <V, P> Function<V, Cached<V, P>> createDiskCached(
            final P policy) {
        return new Function<V, Cached<V, P>>() {
            @Override
            public Cached<V, P> apply(V value) {
                return new Cached<>(value, policy, true);
            }
        };
    }

    private static <V, P> Function<V, Cached<V, P>> createObservableCached(
            final Function<V, P> policyCreator) {
        return new Function<V, Cached<V, P>>() {
            @Override
            public Cached<V, P> apply(V value) throws Exception {
                return new Cached<>(value, policyCreator.apply(value), false);
            }
        };
    }

    /**
     * Transforms a {@link Single} into an {@link Observable} returning a disk cached version of the
     * latest Value seen for the same key followed by the {@link Single} result.
     * <p/>
     * The execution assures that the cached Value, if available, will be returned first. If no
     * Value is cached, or its Policy is not validated, then the current Value and Policy are
     * deleted silently and just the value of the {@link Single} is returned.
     *
     * @param single {@link Single} operation whose result is to be cached
     * @param key string value under where the values will be stored
     * @return an {@link Observable} that will return a cached Value followed by the result of
     *         executing single
     */
    public Observable<Cached<V, P>> transform(Single<V> single, String key) {
        return transform(single, key, book, policyCreator, policyValidator);
    }
}
