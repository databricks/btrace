/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.btrace.instr.templates.impl;

import static org.objectweb.asm.Opcodes.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.openjdk.btrace.core.MethodID;
import org.openjdk.btrace.core.annotations.Sampled;
import org.openjdk.btrace.instr.Assembler;
import org.openjdk.btrace.instr.Constants;
import org.openjdk.btrace.instr.MethodInstrumentorHelper;
import org.openjdk.btrace.instr.MethodTracker;
import org.openjdk.btrace.instr.templates.BaseTemplateExpander;
import org.openjdk.btrace.instr.templates.Template;
import org.openjdk.btrace.instr.templates.TemplateExpanderVisitor;
import org.openjdk.btrace.runtime.Interval;

/**
 * This expander takes care of macros related to all the sampling and timing functionality
 *
 * @author Jaroslav Bachorik
 */
public class MethodTrackingExpander extends BaseTemplateExpander {
  /**
   * Will provide necessary calls to enable sampling and timing the method or method call.
   *
   * <p>Accepts the following tags
   *
   * <ul>
   *   <li>{@code $TIMED} - enables the timing support
   *   <li>{@code $SAMPLER=[Const | Adaptive]} - selects a sampler, if any
   *   <li>{@code $MEAN=<mean>} - only when sampling; the mean number of hits between samples
   *   <li>{@code $METHODID=<id>} - id generated by {@linkplain
   *       MethodID#getMethodId(java.lang.String, java.lang.String, java.lang.String)}
   *   <li>{@code $LEVEL=<cond>} - level match condition
   * </ul>
   */
  public static final Template ENTRY = new Template("mc$entry", "()V");
  /** Will insert the code to obtain the execution duration. */
  public static final Template DURATION = new Template("mc$dur", "()J");
  /**
   * Will generate the branching logic (if sampling) and/or retrieve the timestamp for the end of
   * execution.
   *
   * <p>Accepts the following tags
   *
   * <ul>
   *   <li>{@code $TIMED} - enables the timing support
   *   <li>
   *   <li>{@code $METHODID=<id>} - id generated by {@linkplain
   *       MethodID#getMethodId(java.lang.String, java.lang.String, java.lang.String)}
   * </ul>
   */
  public static final Template TEST_SAMPLE = new Template("mc$test", "()V");
  /**
   * Will create the jump target for the else part of the condition generated by {@link
   * MethodTrackingExpander#TEST_SAMPLE} template.
   */
  public static final Template ELSE_SAMPLE = new Template("mc$else", "()V");
  /** This must be inserted at the exit point when using adaptive sampling. */
  public static final Template EXIT = new Template("mc$exit", "()V");
  /** Will reset the expander state - useful for multiple return points. */
  public static final Template RESET = new Template("mc$reset", "()V");

  public static final String $TIMED = "timed";
  public static final String $MEAN = "mean";
  public static final String $SAMPLER = "sampler";
  public static final String $METHODID = "methodid";
  public static final String $LEVEL = "level";

  private static final String METHOD_COUNTER_CLASS = MethodTracker.class.getName().replace(".", "/");;
  private final int methodId;
  private final Collection<Interval> levelIntervals = new ArrayList<>();
  private final MethodInstrumentorHelper mHelper;
  private boolean isTimed = false;
  private boolean isSampled = false;
  private Sampled.Sampler samplerKind = Sampled.Sampler.None;
  private int samplerMean = -1;
  private int entryTsVar = Integer.MIN_VALUE;
  private int sHitVar = Integer.MIN_VALUE;
  private int durationVar = Integer.MIN_VALUE;
  private int globalLevelVar = Integer.MIN_VALUE;
  private boolean durationComputed = false;
  private Label elseLabel = null;
  private Label samplerLabel = null;

  public MethodTrackingExpander(int methodId, MethodInstrumentorHelper mHelper) {
    super(ENTRY, DURATION, TEST_SAMPLE, ELSE_SAMPLE, EXIT, RESET);
    this.methodId = methodId;
    this.mHelper = mHelper;
  }

  @Override
  protected void recordTemplate(Template t) {
    if (ENTRY.equals(t)) {
      Map<String, String> m = t.getTagMap();
      isTimed = m.containsKey($TIMED);

      String sKind = m.get($SAMPLER);
      String sMean = m.get($MEAN);

      String levelStr = m.get($LEVEL);

      if (levelStr != null && !levelStr.isEmpty()) {
        Interval itv = Interval.fromString(levelStr);
        levelIntervals.add(itv);
      }

      if (sKind != null) {
        if (samplerMean != 0) {
          int mean = sMean != null ? Integer.parseInt(sMean) : Sampled.MEAN_DEFAULT;

          // The average mean sampler has the highest precedence
          if (samplerKind != Sampled.Sampler.Const) {
            samplerKind = Sampled.Sampler.valueOf(sKind);
          }

          if (samplerMean == -1) {
            samplerMean = mean;
          } else if (samplerMean > 0) {
            samplerMean = Math.min(samplerMean, mean);
          }

          isSampled = samplerMean > 0;
        }
      } else {
        // hitting a method in non-sampled mode means that no
        // sampling will be done even though other scripts request it
        samplerMean = 0;
        isSampled = false;
      }
    }
  }

  @Override
  protected Result expandTemplate(TemplateExpanderVisitor v, Template t) {
    int localMethodId = methodId;
    String sMethodId = t.getTagMap().get($METHODID);
    if (sMethodId != null) {
      localMethodId = Integer.parseInt(sMethodId);
    }
    int mid = localMethodId;

    if (tryExpandEntry(t, mid, v)
        || tryExpandTest(t, mid, v)
        || tryExpandElse(t, v)
        || tryExpandDuration(t, v)
        || tryExpandExit(t, mid, v)
        || tryExpandReset(t, v)) return Result.EXPANDED;

    return Result.IGNORED;
  }

  private boolean tryExpandEntry(Template t, int mid, TemplateExpanderVisitor v) {
    if (ENTRY.equals(t)) {
      if (isSampled) {
        MethodTracker.registerCounter(mid, samplerMean);
        if (isTimed) {
          v.expand(new TimingSamplerEntry(mid));
        } else {
          v.expand(new SamplerEntry(mid));
        }
      } else {
        if (isTimed) {
          v.expand(new TimingEntry());
        }
      }
      return true;
    }
    return false;
  }

  private boolean tryExpandTest(Template t, int mid, TemplateExpanderVisitor v) {
    if (TEST_SAMPLE.equals(t)) {
      samplerLabel = new Label();
      boolean collectTime = t.getTagMap().containsKey($TIMED);
      boolean expanded = false;
      if (isSampled) {
        v.expand(new SamplerTest());
        if (isTimed && collectTime) {
          v.expand(new TimingSamplerTest(mid));
        }
        expanded = true;
      } else {
        if (isTimed && collectTime) {
          v.expand(new TimingTest());
          expanded = true;
        }
      }
      if (expanded) {
        v.asm().label(samplerLabel);
        mHelper.insertFrameSameStack(samplerLabel);
      }
      samplerLabel = null;
      return true;
    }
    return false;
  }

  private boolean tryExpandElse(Template t, TemplateExpanderVisitor v) {
    if (ELSE_SAMPLE.equals(t)) {
      v.expand(new Else());
      return true;
    }
    return false;
  }

  private boolean tryExpandDuration(Template t, TemplateExpanderVisitor v) {
    if (DURATION.equals(t)) {
      v.expand(new Duration());
      return true;
    }
    return false;
  }

  private boolean tryExpandExit(Template t, int mid, TemplateExpanderVisitor v) {
    if (EXIT.equals(t)) {
      v.expand(new Exit(mid));
      return true;
    }
    return false;
  }

  private boolean tryExpandReset(Template t, TemplateExpanderVisitor v) {
    if (RESET.equals(t)) {
      entryTsVar = Integer.MIN_VALUE;
      sHitVar = Integer.MIN_VALUE;
      globalLevelVar = Integer.MIN_VALUE;
      durationComputed = false;
      return true;
    }
    return false;
  }

  @Override
  public void resetState() {
    durationComputed = false;
  }

  private Label addLevelChecks(TemplateExpanderVisitor e) {
    return addLevelChecks(e, null, null);
  }

  private Label addLevelChecks(TemplateExpanderVisitor e, Runnable initializer) {
    return addLevelChecks(e, null, initializer);
  }

  @SuppressWarnings("UnusedReturnValue")
  private Label addLevelChecks(TemplateExpanderVisitor e, Label skip) {
    return addLevelChecks(e, skip, null);
  }

  private Label addLevelChecks(TemplateExpanderVisitor e, Label skip, Runnable initializer) {
    Label skipTarget = null;
    if (!levelIntervals.isEmpty()) {
      Assembler asm = new Assembler(e, mHelper);
      List<Interval> optimized = Interval.invert(levelIntervals);
      boolean generateBranch = true;
      if (optimized.size() == 1) {
        Interval i = optimized.get(0);
        if (i.isNone() || (i.getA() == Integer.MIN_VALUE && i.getB() == -1)) {
          // level check will always pass
          generateBranch = false;
        }
      }
      if (generateBranch) {
        if (initializer != null) {
          // initialize variables used in the conditional code
          initializer.run();
        }

        skipTarget = skip != null ? skip : new Label();

        for (Interval i : optimized) {
          Label nextCheck = new Label();
          if (globalLevelVar == Integer.MIN_VALUE) {
            asm.getStatic(e.getProbeClassName(true), Constants.BTRACE_LEVEL_FLD, Constants.INT_DESC)
                .dup();
            globalLevelVar = e.storeAsNew();
          } else {
            asm.loadLocal(Type.INT_TYPE, globalLevelVar);
          }
          boolean stackConsumed = false;
          if (i.getA() > Integer.MIN_VALUE) {
            stackConsumed = true;
            if (i.getA() == 0) {
              asm.jump(IFLT, nextCheck);
            } else {
              asm.ldc(i.getA()).jump(IF_ICMPLT, nextCheck);
            }
          }
          if (i.getB() < Integer.MAX_VALUE) {
            if (stackConsumed) {
              asm.loadLocal(Type.INT_TYPE, globalLevelVar);
            }
            if (i.getB() == 0) {
              asm.jump(IFLE, skipTarget);
            } else {
              asm.ldc(i.getB()).jump(IF_ICMPLE, skipTarget);
            }
          } else {
            Label l = new Label();
            asm.label(l);
            mHelper.insertFrameSameStack(l);
            asm.jump(GOTO, skipTarget);
          }
          asm.label(nextCheck);
          mHelper.insertFrameSameStack(nextCheck);
        }
      }
    }
    return skipTarget;
  }

  private class TimingSamplerEntry implements Consumer<TemplateExpanderVisitor> {

    private final int mid;

    public TimingSamplerEntry(int mid) {
      this.mid = mid;
    }

    @Override
    public void consume(TemplateExpanderVisitor e) {
      Assembler asm = e.asm();

      // initialize variables used in the coniditional code
      if (durationVar == Integer.MIN_VALUE) {
        asm.ldc(0L);
        durationVar = e.storeAsNew();
      }

      if (sHitVar == Integer.MIN_VALUE && entryTsVar == Integer.MIN_VALUE) {
        Label skipTarget =
            addLevelChecks(
                e,
                () -> {
                  if (entryTsVar == Integer.MIN_VALUE) {
                    asm.ldc(0L);
                    entryTsVar = e.storeAsNew();
                  }
                  if (sHitVar == Integer.MIN_VALUE) {
                    asm.ldc(0);
                    sHitVar = e.storeAsNew();
                  }
                });
        asm.ldc(mid);
        switch (samplerKind) {
          case Const:
            {
              asm.invokeStatic(METHOD_COUNTER_CLASS, "hitTimed", "(I)J");
              break;
            }
          case Adaptive:
            {
              asm.invokeStatic(METHOD_COUNTER_CLASS, "hitTimedAdaptive", "(I)J");
              break;
            }
          default:
            // do nothing
        }

        asm.dup2();
        if (entryTsVar == Integer.MIN_VALUE) {
          entryTsVar = e.storeAsNew();
        } else {
          asm.storeLocal(Type.LONG_TYPE, entryTsVar);
        }
        e.visitInsn(L2I);
        if (sHitVar == Integer.MIN_VALUE) {
          sHitVar = e.storeAsNew();
        } else {
          asm.storeLocal(Type.INT_TYPE, sHitVar);
        }
        if (skipTarget != null) {
          asm.label(skipTarget);
          mHelper.insertFrameSameStack(skipTarget);
        }
      }
    }
  }

  private class SamplerEntry implements Consumer<TemplateExpanderVisitor> {

    private final int mid;

    public SamplerEntry(int mid) {
      this.mid = mid;
    }

    @Override
    public void consume(TemplateExpanderVisitor e) {
      Assembler asm = e.asm();

      if (sHitVar == Integer.MIN_VALUE) {
        Label skipTarget =
            addLevelChecks(
                e,
                () -> {
                  if (sHitVar == Integer.MIN_VALUE) {
                    asm.ldc(0);
                    sHitVar = e.storeAsNew();
                  }
                });
        asm.ldc(mid);
        switch (samplerKind) {
          case Const:
            {
              asm.invokeStatic(METHOD_COUNTER_CLASS, "hit", "(I)Z");
              break;
            }
          case Adaptive:
            {
              asm.invokeStatic(METHOD_COUNTER_CLASS, "hitAdaptive", "(I)Z");
              break;
            }
          default:
            // do nothing
        }
        if (sHitVar == Integer.MIN_VALUE) {
          sHitVar = e.storeAsNew();
        } else {
          asm.storeLocal(Type.INT_TYPE, sHitVar);
        }
        if (skipTarget != null) {
          asm.label(skipTarget);
          mHelper.insertFrameSameStack(skipTarget);
        }
      }
    }
  }

  private class TimingEntry implements Consumer<TemplateExpanderVisitor> {
    @Override
    public void consume(TemplateExpanderVisitor e) {
      Assembler asm = e.asm();
      if (entryTsVar == Integer.MIN_VALUE) {
        if (durationVar == Integer.MIN_VALUE) {
          asm.ldc(0L);
          durationVar = e.storeAsNew();
        }
        Label skipTarget =
            addLevelChecks(
                e,
                () -> {
                  asm.ldc(0L);
                  entryTsVar = e.storeAsNew();
                });

        asm.invokeStatic("java/lang/System", "nanoTime", "()J");
        if (entryTsVar == Integer.MIN_VALUE) {
          entryTsVar = e.storeAsNew();
        } else {
          asm.storeLocal(Type.LONG_TYPE, entryTsVar);
        }
        if (skipTarget != null) {
          asm.label(skipTarget);
          mHelper.insertFrameSameStack(skipTarget);
        }
      }
    }
  }

  private class SamplerTest implements Consumer<TemplateExpanderVisitor> {
    @Override
    public void consume(TemplateExpanderVisitor e) {
      if (sHitVar != Integer.MIN_VALUE) {
        elseLabel = new Label();
        addLevelChecks(e, samplerLabel);
        e.asm().loadLocal(Type.INT_TYPE, sHitVar).jump(IFEQ, elseLabel);
      }
    }
  }

  private class TimingSamplerTest implements Consumer<TemplateExpanderVisitor> {

    private final int mid;

    public TimingSamplerTest(int mid) {
      this.mid = mid;
    }

    @Override
    public void consume(TemplateExpanderVisitor e) {
      if (!durationComputed) {
        if (entryTsVar != Integer.MIN_VALUE) {
          e.asm()
              .ldc(mid)
              .invokeStatic(METHOD_COUNTER_CLASS, "getEndTs", "(I)J")
              .loadLocal(Type.LONG_TYPE, entryTsVar)
              .sub(Type.LONG_TYPE);
        } else {
          e.asm().ldc(0L);
        }
        e.asm().storeLocal(Type.LONG_TYPE, durationVar);
        durationComputed = true;
      }
    }
  }

  private class TimingTest implements Consumer<TemplateExpanderVisitor> {
    @Override
    public void consume(TemplateExpanderVisitor e) {
      if (!durationComputed) {
        addLevelChecks(e, samplerLabel);
        if (entryTsVar != Integer.MIN_VALUE) {
          e.asm()
              .invokeStatic("java/lang/System", "nanoTime", "()J")
              .loadLocal(Type.LONG_TYPE, entryTsVar)
              .sub(Type.LONG_TYPE);
        } else {
          e.asm().ldc(0L);
        }
        e.asm().storeLocal(Type.LONG_TYPE, durationVar);
        durationComputed = true;
      }
    }
  }

  private class Else implements Consumer<TemplateExpanderVisitor> {
    @Override
    public void consume(TemplateExpanderVisitor e) {
      if (elseLabel != null) {
        e.asm().label(elseLabel);
        mHelper.insertFrameSameStack(elseLabel);
        elseLabel = null;
      }
    }
  }

  private class Exit implements Consumer<TemplateExpanderVisitor> {
    private final int mid;

    public Exit(int mid) {
      this.mid = mid;
    }

    @Override
    public void consume(TemplateExpanderVisitor e) {
      if (samplerKind == Sampled.Sampler.Adaptive) {
        Label l = new Label();
        e.asm()
            .loadLocal(Type.INT_TYPE, sHitVar)
            .jump(IFEQ, l)
            .ldc(mid)
            .invokeStatic(METHOD_COUNTER_CLASS, "updateEndTs", "(I)V")
            .label(l);
        mHelper.insertFrameSameStack(l);
      }
    }
  }

  private class Duration implements Consumer<TemplateExpanderVisitor> {
    @Override
    public void consume(TemplateExpanderVisitor e) {
      if (!durationComputed) {
        e.asm().ldc(0L);
      } else {
        e.asm().loadLocal(Type.LONG_TYPE, durationVar);
      }
    }
  }
}
