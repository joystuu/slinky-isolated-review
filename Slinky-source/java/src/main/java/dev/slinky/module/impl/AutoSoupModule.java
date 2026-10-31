package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.ItemPolicyService;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.inventory.ConsumableAccess;
import dev.slinky.client.inventory.ConsumableDelay;
import dev.slinky.client.rotation.RecoveredRandom;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import dev.slinky.setting.NumberSetting;

public final strictfp class AutoSoupModule extends EvidenceModule {
   private final AutoSoupModule.State state = new AutoSoupModule.State();
   private final ConsumableAccess.Live access;
   private final ConsumableDelay delay = new ConsumableDelay();
   private final RecoveredRandom.Smooth skip = new RecoveredRandom.Smooth();

   public AutoSoupModule(ModuleManager modules) {
      super("autosoup", "Auto Soup", ModuleCategory.UTILITY);
      this.access = new ConsumableAccess.Live(this, modules);
      this.itemPolicy("allowed_items");
   }

   @Override
   public strictfp boolean listens(int type) {
      return type == 0 || type == 4 || type == 5;
   }

   @Override
   protected strictfp void onEnable(ClientContext c) {
      this.state.enable();
   }

   @Override
   public strictfp void onEvent(ClientContext c, SemanticEvent e) {
      if (e.type == 4 && e.module == this) {
         if (this.state.cancelDisable()) {
            e.cancelled = true;
         }
      } else if (e.type == 0) {
         this.access.context = c;
         this.state
            .tick(
               this.access,
               new AutoSoupModule.Policy() {
                  @Override
                  public strictfp boolean matches(int type) {
                     return AutoSoupModule.this.flag("override_item")
                        ? ItemPolicyService.contains(AutoSoupModule.this.names("allowed_items"), type)
                        : type == 282 || type == 413;
                  }

                  @Override
                  public strictfp boolean skipCandidate() {
                     return AutoSoupModule.this.skip.gate(0.01F, 0.08F, 30.0, RecoveredRandom.now(), RecoveredRandom.SHARED);
                  }

                  @Override
                  public strictfp float delay(float base) {
                     return AutoSoupModule.this.delay.soup(base, AutoSoupModule.this.number("randomization"), RecoveredRandom.now(), RecoveredRandom.SHARED);
                  }
               },
               this.number("single_soup_max_hp"),
               this.number("double_soup_max_hp"),
               this.number("delay_before"),
               this.number("delay_between"),
               this.number("delay_after"),
               this.flag("drop_bowls")
            );
      }
   }

   @Override
   public strictfp boolean afterSettingControl(String key, ClientContext c) {
      if (!"single_soup_max_hp".equals(key)) {
         return false;
      } else {
         NumberSetting single = (NumberSetting)this.findSetting(key);
         NumberSetting twice = (NumberSetting)this.findSetting("double_soup_max_hp");
         if (single.isConfigured() && twice.isConfigured() && twice.floatValue() > single.floatValue()) {
            twice.set(Double.valueOf((double)single.floatValue()));
            return true;
         } else {
            return false;
         }
      }
   }

   @Override
   protected strictfp void onDisable(ClientContext c) {
      this.access.cleanup();
   }

   @Override
   public strictfp void onWorldUnload(ClientContext c) {
      this.access.cleanup();
      this.state.enable();
   }

   public interface Policy {
      boolean matches(int var1);

      boolean skipCandidate();

      float delay(float var1);
   }

   public static final class State {
      public int phase;
      public int selected;
      public int previous;
      public boolean cancel;
      public boolean reuse;
      public boolean repeat;
      public boolean soup;
      public float timer;

      public strictfp void enable() {
         this.phase = 0;
         this.reuse = false;
         this.timer = 0.0F;
         this.cancel = false;
      }

      public strictfp boolean cancelDisable() {
         if (this.phase != 0 && this.phase != 6) {
            this.cancel = true;
            return true;
         } else {
            return false;
         }
      }

      private strictfp void after(AutoSoupModule.Policy p, float between) {
         this.phase = 5;
         if (this.selected != this.previous) {
            this.timer = this.timer + p.delay(between);
            if (this.timer <= 0.0F) {
               this.timer++;
            }
         }
      }

      public strictfp void tick(ConsumableAccess a, AutoSoupModule.Policy p, float single, float twice, float before, float between, float after, boolean drop) {
         if (!this.cancel || this.phase != 6 && this.phase != 0) {
            if (!a.ready()) {
               this.reuse = false;
               this.phase = 0;
            } else {
               for (int pass = 0; pass < 3; pass++) {
                  if (this.timer > 0.0F) {
                     this.timer += -1.0F;
                     return;
                  }

                  switch (this.phase) {
                     case 0:
                        float hp = a.health();
                        if (hp > single) {
                           return;
                        }

                        this.timer = this.timer + p.delay(before);
                        this.repeat = hp <= twice;
                        this.phase = 1;
                        break;
                     case 1:
                        int start = a.current();
                        int found = -1;

                        for (int i = 0; i < 9; i++) {
                           int candidate = (start + i) % 9;
                           if (candidate != start || !this.reuse) {
                              int type = a.type(candidate);
                              if (p.matches(type)) {
                                 this.soup = type == 413 || type == 282;
                                 found = candidate;
                                 if (!p.skipCandidate()) {
                                    break;
                                 }
                              }
                           }
                        }

                        if (found == -1) {
                           this.phase = this.reuse ? 5 : 6;
                           this.reuse = false;
                           return;
                        }

                        this.selected = found;
                        if (!this.reuse) {
                           this.previous = a.current();
                           a.remember();
                        }

                        this.reuse = false;
                        this.phase = this.selected == this.previous ? 3 : 2;
                        break;
                     case 2:
                        if (!a.claim()) {
                           this.phase = 0;
                           return;
                        }

                        a.select(this.selected);
                        this.phase = 3;
                        this.timer = this.timer + p.delay(between);
                        if (a.usingItem() && this.timer <= 0.0F) {
                           this.timer++;
                        }
                        break;
                     case 3:
                        a.use();
                        if (drop && this.soup) {
                           this.phase = 4;
                           this.timer = this.timer + p.delay(between);
                           if (this.timer <= 0.0F) {
                              this.timer++;
                           }
                        } else {
                           this.after(p, between);
                        }
                        break;
                     case 4:
                        a.drop();
                        this.after(p, between);
                        break;
                     case 5:
                        a.release();
                        if (this.repeat) {
                           this.repeat = false;
                           this.reuse = true;
                           this.phase = 1;
                        } else {
                           a.select(this.previous);
                           this.phase = 6;
                        }
                        break;
                     case 6:
                        this.reuse = false;
                        this.timer = this.timer + p.delay(after);
                        this.phase = 0;
                        break;
                     default:
                        return;
                  }
               }
            }
         } else {
            a.disable();
         }
      }
   }
}
