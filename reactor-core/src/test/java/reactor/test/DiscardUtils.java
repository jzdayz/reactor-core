/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.test;

import java.time.Duration;
import java.util.concurrent.ForkJoinPool;

import org.reactivestreams.Subscription;

import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Fuseable.QueueSubscription;
import reactor.core.publisher.InnerSubscription;
import reactor.util.annotation.Nullable;

/**
 * Test utilities that helps with discard/leaks/cancellation acknowledgement.
 *
 * @author Simon Baslé
 */
public class DiscardUtils {

	/**
	 * Create a test {@link Subscription} that acknowledges cancellation immediately by
	 * calling {@link CoreSubscriber#onCancelled()}.
	 * <p>
	 * The subscription is compatible with {@link QueueSubscription} (fuseable), although
	 * it always negotiate the fusion mode to be NONE.
	 *
	 * @param actual the downstream {@link CoreSubscriber} this subscription will be passed
	 * to, and which will be acknowledged
	 * @param <T> the type of data
	 * @return the immediately acknowledging {@link Subscription}
	 */
	public static <T> Subscription acknowledgingSubscription(CoreSubscriber<? super T> actual) {
		return acknowledgingSubscription(actual, Duration.ZERO);
	}

	/**
	 * Create a test {@link Subscription} that acknowledges cancellation after a specified
	 * delay by calling {@link CoreSubscriber#onCancelled()} from a separate thread.
	 * <p>
	 * The subscription is compatible with {@link QueueSubscription} (fuseable), although
	 * it always negotiate the fusion mode to be NONE.
	 *
	 * @param actual the downstream {@link CoreSubscriber} this subscription will be passed
	 * to, and which will be acknowledged after a delay
	 * @param delay the {@link Duration} delay
	 * @param <T> the type of data
	 * @return the acknowledging {@link Subscription}
	 */
	public static <T> Subscription acknowledgingSubscription(CoreSubscriber<? super T> actual, Duration delay) {
		return new AcknowledgingSubscription<>(actual, delay);
	}

	/**
	 * Create a test {@link Subscription} that emits a pre-defined value AFTER it has been
	 * cancelled via {@link Subscription#cancel()} (provided there has been some request).
	 * If {@code ack} is set to true, the subscription additionally acknowledges the
	 * cancellation is done via {@link CoreSubscriber#onCancelled()}.
	 * <p>
	 * The subscription is compatible with {@link QueueSubscription} (fuseable), although
	 * it always negotiate the fusion mode to be NONE.
	 *
	 * @param actual the downstream {@link CoreSubscriber} this subscription will be passed
	 * to, and which will be acknowledged
	 * @param lateOnNext the late onNext data to be emitted post cancellation
	 * @param ack whether or not to acknowledge the cancellation after late item was emitted
	 * @param <T> the type of data
	 * @return the {@link Subscription} which emits immediately after being cancelled
	 */
	public static <T> Subscription lateOnNextSubscription(CoreSubscriber<? super T> actual, T lateOnNext, boolean ack) {
		return lateOnNextSubscription(actual, lateOnNext, Duration.ZERO, ack);
	}

	/**
	 * Create a test {@link Subscription} that emits a pre-defined value some time AFTER it
	 * has been cancelled via {@link Subscription#cancel()} (provided there has been some request).
	 * If {@code ack} is set to true, the subscription additionally acknowledges the
	 * cancellation is done via {@link CoreSubscriber#onCancelled()}. This is done on a
	 * dedicated thread.
	 * <p>
	 * The subscription is compatible with {@link QueueSubscription} (fuseable), although
	 * it always negotiate the fusion mode to be NONE.
	 *
	 * @param actual the downstream {@link CoreSubscriber} this subscription will be passed
	 * to, and which will be acknowledged
	 * @param lateOnNext the late onNext data to be emitted post cancellation
	 * @param delay the delay after which to emit late data (and optionally acknowledge cancel)
	 * @param ack whether or not to acknowledge the cancellation after late item was emitted
	 * @param <T> the type of data
	 * @return the {@link Subscription} which emits immediately after being cancelled
	 */
	public static <T> Subscription lateOnNextSubscription(CoreSubscriber<? super T> actual, T lateOnNext, Duration delay, boolean ack) {
		if (ack) {
			return new LateAcknowledgingSubscription<>(actual, delay, lateOnNext);
		}
		return new LateVanillaSubscription<>(actual, delay, lateOnNext);
	}



	static final class AcknowledgingSubscription<T> implements QueueSubscription<T>,
	                                                                  InnerSubscription<T> {

		final CoreSubscriber<? super T> actual;
		final Duration ackDelay;

		public AcknowledgingSubscription(CoreSubscriber<? super T> actual, Duration ackDelay) {
			this.actual = actual;
			this.ackDelay = ackDelay;
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.actual;
		}

		@Nullable
		@Override
		public T poll() {
			return null;
		}

		@Override
		public void request(long n) {
			//NO-OP
		}

		@Override
		public void cancel() {
			if (!ackDelay.isZero()) {
				ForkJoinPool.commonPool().execute(() -> {
					try {
						Thread.sleep(ackDelay.toMillis());
					}
					catch (InterruptedException e) {
						e.printStackTrace();
					}
					finally {
						actual.onCancelled();
					}
				});
				return;
			}

			actual.onCancelled();
		}

		@Override
		public int requestFusion(int requestedMode) {
			return Fuseable.NONE;
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public boolean isEmpty() {
			return true;
		}

		@Override
		public void clear() {
			//NO-OP
		}
	}

	static final class LateAcknowledgingSubscription<T> implements QueueSubscription<T>,
	                                                                      InnerSubscription<T> {

		final CoreSubscriber<? super T> actual;
		final Duration ackDelay;
		final T lateOnNext;

		volatile boolean requested;

		public LateAcknowledgingSubscription(CoreSubscriber<? super T> actual, Duration ackDelay, T lateOnNext) {
			this.actual = actual;
			this.ackDelay = ackDelay;
			this.lateOnNext = lateOnNext;
		}

		@Override
		public CoreSubscriber<? super T> actual() {
			return this.actual;
		}

		@Nullable
		@Override
		public T poll() {
			return null;
		}

		@Override
		public void request(long n) {
			requested = true;
		}

		@Override
		public void cancel() {
			if (!ackDelay.isZero()) {
				ForkJoinPool.commonPool().execute(() -> {
					try {
						Thread.sleep(ackDelay.toMillis());
					}
					catch (InterruptedException e) {
						e.printStackTrace();
					}
					finally {
						if (requested) {
							actual.onNext(lateOnNext);
						}
						actual.onCancelled();
					}
				});
				return;
			}

			if (requested) {
				actual.onNext(lateOnNext);
			}
			actual.onCancelled();
		}

		@Override
		public int requestFusion(int requestedMode) {
			return Fuseable.NONE;
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public boolean isEmpty() {
			return true;
		}

		@Override
		public void clear() {
			//NO-OP
		}
	}

	static final class LateVanillaSubscription<T> implements QueueSubscription<T> {

		final CoreSubscriber<? super T> actual;
		final Duration ackDelay;
		final T lateOnNext;

		volatile boolean requested;

		public LateVanillaSubscription(CoreSubscriber<? super T> actual, Duration ackDelay, T lateOnNext) {
			this.actual = actual;
			this.ackDelay = ackDelay;
			this.lateOnNext = lateOnNext;
		}

		@Nullable
		@Override
		public T poll() {
			return null;
		}

		@Override
		public void request(long n) {
			requested = true;
		}

		@Override
		public void cancel() {
			if (!ackDelay.isZero()) {
				ForkJoinPool.commonPool().execute(() -> {
					try {
						Thread.sleep(ackDelay.toMillis());
					}
					catch (InterruptedException e) {
						e.printStackTrace();
					}
					finally {
						if (requested) {
							actual.onNext(lateOnNext);
						}
					}
				});
				return;
			}

			if (requested) {
				actual.onNext(lateOnNext);
			}
		}

		@Override
		public int requestFusion(int requestedMode) {
			return Fuseable.NONE;
		}

		@Override
		public int size() {
			return 0;
		}

		@Override
		public boolean isEmpty() {
			return true;
		}

		@Override
		public void clear() {
			//NO-OP
		}
	}
}