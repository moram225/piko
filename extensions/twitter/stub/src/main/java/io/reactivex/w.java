package io.reactivex;

import io.reactivex.disposables.c;

/** Compile-time stub: X's relocated RxJava SingleObserver (io.reactivex.w). */
public interface w<T> {
    void onSubscribe(c disposable);

    void onSuccess(T value);

    void onError(Throwable error);
}
