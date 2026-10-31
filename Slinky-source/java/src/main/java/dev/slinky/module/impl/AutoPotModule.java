package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.inventory.ConsumableAccess;
import dev.slinky.client.inventory.ConsumableDelay;
import dev.slinky.client.inventory.InventoryActions;
import dev.slinky.client.rotation.RecoveredRandom;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;

public final strictfp class AutoPotModule extends EvidenceModule {
   private final AutoPotModule.State state = new AutoPotModule.State();
   private final ConsumableAccess.Live access;
   private final ConsumableDelay delay = new ConsumableDelay();

   public AutoPotModule(ModuleManager modules) {
      super("autopot", "Auto Pot", ModuleCategory.UTILITY);
      this.access = new ConsumableAccess.Live(this, modules);
   }

   @Override
   public strictfp boolean listens(int type) {
      return type == 0 || type == 4 || type == 5;
   }

   @Override
   protected strictfp void onEnable(ClientContext c) {
      this.access.context = c;
      this.state.enable(this.access, this.flag("prevent_waste"), this.flag("double_splash"));
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
            .tick(this.access, () -> this.delay.switching(this.number("delay"), RecoveredRandom.now(), RecoveredRandom.SHARED), this.flag("use_non_health"));
      }
   }

   @Override
   protected strictfp void onDisable(ClientContext c) {
      this.access.cleanup();
      this.state.phase = 0;
      this.state.uses = 0;
   }

   @Override
   public strictfp void onWorldUnload(ClientContext c) {
      this.access.cleanup();
      this.state.phase = 0;
      this.state.uses = 0;
      this.state.continuing = false;
      this.state.timer = 0.0F;
   }

   public interface Delay {
      float sample();
   }

   public static final class State {
      public int phase;
      public int uses;
      public int pending;
      public int selected;
      public int previous;
      public boolean continuing;
      public float timer;

      public strictfp void enable(ConsumableAccess a, boolean prevent, boolean multiple) {
         if (a.ready()) {
            int count = InventoryActions.truncateDouble(((double)(a.maximumHealth() - a.health()) + 0.5) * 0.125);
            if (count == 0) {
               count = 1;
               if (prevent) {
                  this.phase = 4;
                  return;
               }
            }

            if (multiple) {
               this.uses = count;
               this.pending = 0;
            } else {
               this.uses = 1;
               this.pending = prevent ? count - 1 : 5;
            }
         }
      }

      public strictfp boolean cancelDisable() {
         if (this.phase != 4 && this.pending > 0) {
            this.uses++;
            this.pending--;
            return true;
         } else {
            return false;
         }
      }

      public strictfp void tick(ConsumableAccess a, AutoPotModule.Delay delay, boolean nonHealth) {
         for (int pass = 0; pass < 4; pass++) {
            if (this.timer > 0.0F) {
               this.timer += -1.0F;
               break;
            }

            if (!a.ready()) {
               this.continuing = false;
               this.phase = 4;
               a.disable();
               return;
            }

            switch (this.phase) {
               case 0:
                  int slot = 0;

                  while (slot < 9 && (a.type(slot) != 373 || (a.damage(slot) & 16384) == 0 || (a.damage(slot) & 15) != 5 && !nonHealth)) {
                     slot++;
                  }

                  if (slot == 9) {
                     this.uses = 0;
                     this.phase = this.continuing ? 3 : 4;
                     this.continuing = false;
                     if (this.phase == 4) {
                        a.disable();
                     }

                     return;
                  }

                  this.selected = slot;
                  if (!this.continuing) {
                     this.previous = a.current();
                     a.remember();
                  }

                  this.continuing = false;
                  this.phase = this.selected == this.previous ? 2 : 1;
                  break;
               case 1:
                  if (!a.claim()) {
                     this.phase = 4;
                     a.disable();
                     return;
                  }

                  a.select(this.selected);
                  this.phase = 2;
                  this.timer = delay.sample() + this.timer;
                  break;
               case 2:
                  if (a.usingItem()) {
                     return;
                  }

                  a.use();
                  this.uses--;
                  this.phase = 3;
                  if (this.selected != this.previous) {
                     this.timer = delay.sample() + 1.0F + this.timer;
                  }
                  break;
               case 3:
                  if (this.uses > 0) {
                     this.continuing = true;
                     this.phase = 0;
                  } else {
                     a.select(this.previous);
                     this.phase = 4;
                  }
                  break;
               case 4:
                  a.release();
                  a.disable();
                  return;
               default:
                  throw new IllegalStateException("Invalid potion phase");
            }
         }

         if (this.phase == 4) {
            a.disable();
         }
      }
   }
}
