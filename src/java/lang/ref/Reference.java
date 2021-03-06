/*
 * Copyright (c) 1997, 2006, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

package java.lang.ref;

import sun.misc.Cleaner;


/**
 * Abstract base class for reference objects.  This class defines the
 * operations common to all reference objects.  Because reference objects are
 * implemented in close cooperation with the garbage collector, this class may
 * not be subclassed directly.
 *
 * @author Mark Reinhold
 * @since 1.2
 */
//封装了其它对象的引用，可以和普通的对象一样操作，在一定的限制条件下，支持和垃圾收集器的交互
//程序有时候也需要在对象回收后被通知，以告知对象的可达性发生变更。
public abstract class Reference<T> {

    /* A Reference instance is in one of four possible internal states:
     *
     *     Active: Subject to special treatment by the garbage collector.  Some
     *     time after the collector detects that the reachability of the
     *     referent has changed to the appropriate state, it changes the
     *     instance's state to either Pending or Inactive, depending upon
     *     whether or not the instance was registered with a queue when it was
     *     created.  In the former case it also adds the instance to the
     *     pending-Reference list.  Newly-created instances are Active.
     *
     *     Pending: An element of the pending-Reference list, waiting to be
     *     enqueued by the Reference-handler thread.  Unregistered instances
     *     are never in this state.
     *
     *     Enqueued: An element of the queue with which the instance was
     *     registered when it was created.  When an instance is removed from
     *     its ReferenceQueue, it is made Inactive.  Unregistered instances are
     *     never in this state.
     *
     *     Inactive: Nothing more to do.  Once an instance becomes Inactive its
     *     state will never change again.
     *
     * The state is encoded in the queue and next fields as follows:
     *
     *     Active: queue = ReferenceQueue with which instance is registered, or
     *     ReferenceQueue.NULL if it was not registered with a queue; next =
     *     null.
     *
     *     Pending: queue = ReferenceQueue with which instance is registered;
     *     next = Following instance in queue, or this if at end of list.
     *
     *     Enqueued: queue = ReferenceQueue.ENQUEUED; next = Following instance
     *     in queue, or this if at end of list.
     *
     *     Inactive: queue = ReferenceQueue.NULL; next = this.
     *
     * With this scheme the collector need only examine the next field in order
     * to determine whether a Reference instance requires special treatment: If
     * the next field is null then the instance is active; if it is non-null,
     * then the collector should treat the instance normally.
     *
     * To ensure that concurrent collector can discover active Reference
     * objects without interfering with application threads that may apply
     * the enqueue() method to those objects, collectors should link
     * discovered objects through the discovered field.
     */
    // 用于保存对象的引用，GC会根据不同Reference来特别对待
    private T referent;         /* Treated specially by GC */

    // 如果需要通知机制，则保存的对对应的队列
    ReferenceQueue<? super T> queue;
    //用于实现一个单向循环链表，用以将保存需要由ReferenceHandler处理的引用
    Reference next;
    transient private Reference<T> discovered;  /* used by VM */


    /* Object used to synchronize with the garbage collector.  The collector
     * must acquire this lock at the beginning of each collection cycle.  It is
     * therefore critical that any code holding this lock complete as quickly
     * as possible, allocate no new objects, and avoid calling user code.
     */
    static private class Lock {
    }

    //锁，用于同步pending队列的进队和出队
    private static Lock lock = new Lock();


    /* List of References waiting to be enqueued.  The collector adds
     * References to this list, while the Reference-handler thread removes
     * them.  This list is protected by the above lock object.
     */
    //垃圾收集器将引用添加到pending队列，ReferenceHandler会从这个队列中取出Reference元素，添加到Reference各自注册的引用队列中。
    //保存一个PENDING的队列，配合上述next一起使用
    private static Reference pending = null;

    /* High-priority thread to enqueue pending References
     */
    //使pending引用进入队列
    //将pending队列里面的Reference实例依次添加到不同的ReferenceQueue
    // 中（取决于Reference里面的queue）。该pending的元素由GC负责加入。
    private static class ReferenceHandler extends Thread {

        ReferenceHandler(ThreadGroup g, String name) {
            super(g, name);
        }

        public void run() {
            for (; ; ) {

                Reference r;
                //获取需要进入队列的pending引用
                synchronized (lock) {
                    if (pending != null) {
                        r = pending;
                        Reference rn = r.next;
                        pending = (rn == r) ? null : rn;  //如果rn==r，说明后继节点为空，给pending赋值null，否则就为后继节点rn。
                        r.next = r;
                    } else {
                        try {
//                            没有pending则等待
                            lock.wait();
                        } catch (InterruptedException x) {
                        }
                        continue;
                    }
                }

                // Fast path for cleaners
                if (r instanceof Cleaner) {
                    ((Cleaner) r).clean();
                    continue;
                }

                //将pending引用放入其注册的队列中
                ReferenceQueue q = r.queue;
                if (q != ReferenceQueue.NULL) q.enqueue(r);
            }
        }
    }

    static {
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        for (ThreadGroup tgn = tg;
             tgn != null;
             tg = tgn, tgn = tg.getParent())
            ;
        Thread handler = new ReferenceHandler(tg, "Reference Handler");
        /* If there were a special system-only priority greater than
         * MAX_PRIORITY, it would be used here
         */
        handler.setPriority(Thread.MAX_PRIORITY);
        handler.setDaemon(true);
        handler.start();
    }


    /* -- Referent accessor and setters -- */

    /**
     * Returns this reference object's referent.  If this reference object has
     * been cleared, either by the program or by the garbage collector, then
     * this method returns <code>null</code>.
     *
     * @return The object to which this reference refers, or
     * <code>null</code> if this reference object has been cleared
     */
    public T get() {
        return this.referent;
    }

    /**
     * Clears this reference object.  Invoking this method will not cause this
     * object to be enqueued.
     * <p>
     * <p> This method is invoked only by Java code; when the garbage collector
     * clears references it does so directly, without invoking this method.
     */
    public void clear() {
        this.referent = null;
    }


    /* -- Queue operations -- */

    /**
     * Tells whether or not this reference object has been enqueued, either by
     * the program or by the garbage collector.  If this reference object was
     * not registered with a queue when it was created, then this method will
     * always return <code>false</code>.
     *
     * @return <code>true</code> if and only if this reference object has
     * been enqueued
     */
    public boolean isEnqueued() {
        /* In terms of the internal states, this predicate actually tests
           whether the instance is either Pending or Enqueued */
        synchronized (this) {
            return (this.queue != ReferenceQueue.NULL) && (this.next != null);
        }
    }

    /**
     * Adds this reference object to the queue with which it is registered,
     * if any.
     * <p>
     * <p> This method is invoked only by Java code; when the garbage collector
     * enqueues references it does so directly, without invoking this method.
     *
     * @return <code>true</code> if this reference object was successfully
     * enqueued; <code>false</code> if it was already enqueued or if
     * it was not registered with a queue when it was created
     */
    public boolean enqueue() {
        return this.queue.enqueue(this);
    }


    /* -- Constructors -- */

    Reference(T referent) {
        this(referent, null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        this.queue = (queue == null) ? ReferenceQueue.NULL : queue;
    }

}
