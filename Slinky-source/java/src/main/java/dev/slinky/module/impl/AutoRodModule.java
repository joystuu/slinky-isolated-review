package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.ModuleActionService;
import dev.slinky.client.RecoveredMath;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.hook.GameHooks;
import dev.slinky.client.inventory.ConsumableDelay;
import dev.slinky.client.inventory.InventoryActions;
import dev.slinky.client.packet.TimingAccess;
import dev.slinky.client.rotation.RecoveredRandom;
import dev.slinky.client.rotation.RotationMath;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;

public final strictfp class AutoRodModule extends EvidenceModule {
   private final ModuleManager modules;
   private final WeaponsModule weapons;
   private final ConsumableDelay switchDelay = new ConsumableDelay();
   private final RecoveredRandom.Smooth throwRandom = new RecoveredRandom.Smooth();
   private final RecoveredRandom.Smooth throwGate = new RecoveredRandom.Smooth();
   private int phase;
   private int previous = -1;
   private int selected;
   private int retract;
   private float timer;
   private boolean cancel;
   private boolean rod;
   private boolean inputSeen;
   private InventoryPlayer owner;

   public AutoRodModule(ModuleManager modules, WeaponsModule weapons) {
      super("autorod", "Auto Rod", ModuleCategory.UTILITY);
      this.modules = modules;
      this.weapons = weapons;
   }

   @Override
   public strictfp boolean listens(int type) {
      return type == 0 || type == 4 || type == 5 || type == 14;
   }

   @Override
   protected strictfp void onEnable(ClientContext c) {
      this.phase = 0;
   }

   private strictfp void restore() {
      if (this.owner != null && this.previous != -1) {
         this.owner.currentItem = this.previous;
      }

      this.previous = -1;
      this.owner = null;
   }

   private strictfp void release() {
      ModuleActionService.release(this);
   }

   private strictfp float delay() {
      return this.flag("randomize_delays")
         ? this.switchDelay.switching(this.number("delay"), RecoveredRandom.now(), RecoveredRandom.SHARED)
         : this.number("delay");
   }

   public static strictfp int choose(int[] types, boolean throwables) {
      int candidate = -1;

      for (int i = 0; i < 9; i++) {
         if ((types[i] == 332 || types[i] == 344) && throwables) {
            return i;
         }

         if (types[i] == 346) {
            candidate = i;
            if (!throwables) {
               return i;
            }
         }
      }

      return candidate;
   }

   private strictfp int scan(InventoryPlayer inventory) {
      int candidate = -1;
      this.rod = false;

      for (int i = 0; i < 9; i++) {
         int type = InventoryActions.type(inventory.getStackInSlot(i));
         if ((type == 332 || type == 344) && this.flag("allow_throwables")) {
            return i;
         }

         if (type == 346) {
            this.rod = true;
            candidate = i;
            if (!this.flag("allow_throwables")) {
               return i;
            }
         }
      }

      return candidate;
   }

   public static strictfp int retractTicks(Vec3 origin, Vec3 target, float speed) {
      float delay;
      if (target == null) {
         delay = 10.0F / speed;
      } else {
         Vec3 closest = new Vec3(
            RotationMath.clamp(target.xCoord - 0.3, target.xCoord + 0.3, origin.xCoord),
            RotationMath.clamp(target.yCoord, target.yCoord + 1.8, origin.yCoord),
            RotationMath.clamp(target.zCoord - 0.3, target.zCoord + 0.3, origin.zCoord)
         );
         delay = (float)(RotationMath.distance(origin, closest) / (double)speed);
      }

      return RecoveredMath.truncateInt(RecoveredMath.ceiling(delay));
   }

   private strictfp float throwingDelay() {
      float speed = this.number("throw_speed");
      if (!this.flag("randomize_delays")) {
         return 20.0F / speed;
      } else {
         double now = RecoveredRandom.now();
         RecoveredRandom r = RecoveredRandom.SHARED;
         float result = 20.0F / (speed * this.throwRandom.sample(0.5F, 1.0F, 1.0, now, r));
         if (this.throwGate.gate(0.01F, 0.03F, 30.0, now, r)) {
            result += r.interval(1.0F, 2.0F);
         }

         return result;
      }
   }

   @Override
   public strictfp void onEvent(ClientContext c, SemanticEvent e) {
      if (e.type == 4 && e.module == this) {
         if (!this.cancel) {
            if (this.isHoldBinding()) {
               this.cancel = true;
            } else {
               if (this.phase == 6) {
                  return;
               }

               if (this.phase == 3) {
                  this.phase = 7;
               }
            }

            e.cancelled = true;
         }
      } else if (e.type == 14) {
         if (!e.active && c.ready() && InventoryActions.type(c.player().getHeldItem()) == 346) {
            if (this.inputSeen) {
               e.cancelled = true;
            } else {
               this.inputSeen = true;
            }
         }
      } else if (e.type == 0 && c.inputReady()) {
         if (this.cancel) {
            this.restore();
            this.release();
            this.modules.requestEnabled(this, false);
            this.cancel = false;
         } else {
            int pass = 0;

            while (true) {
               label180: {
                  if (pass < 3) {
                     MovingObjectPosition hit = c.minecraft().objectMouseOver;
                     if (this.flag("require_not_mouse_over") && hit != null && hit.typeOfHit == MovingObjectType.ENTITY) {
                        this.restore();
                        this.release();
                        this.phase = 6;
                     } else {
                        label190: {
                           if (this.phase == 7) {
                              this.restore();
                              this.release();
                              this.phase = 0;
                              return;
                           }

                           if (this.retract != 0) {
                              this.retract--;
                           }

                           if (this.timer > 0.0F) {
                              this.timer--;
                              if (this.timer > 0.0F) {
                                 break label190;
                              }
                           }

                           InventoryPlayer inv = c.player().inventory;
                           int current = inv.currentItem;
                           if (this.phase > 1 && current != this.selected) {
                              this.release();
                              this.previous = -1;
                              this.owner = null;
                              this.phase = 6;
                           } else if (this.phase <= 5) {
                              if (this.phase == 0) {
                                 int slot = this.scan(inv);
                                 if (slot >= 0 && ModuleActionService.claim(this)) {
                                    this.owner = inv;
                                    this.previous = current;
                                    this.selected = slot;
                                    if (this.flag("always_switch_to_weapon")) {
                                       int weapon = this.weapons.firstAllowedSlot(inv);
                                       if (weapon != -1) {
                                          this.previous = weapon;
                                       }

                                       current = this.previous;
                                    }

                                    this.phase = this.selected == current ? 2 : 1;
                                    break label180;
                                 }

                                 this.phase = 6;
                              } else {
                                 label148: {
                                    if (this.phase == 1) {
                                       inv.currentItem = this.selected;
                                       this.phase = 2;
                                       this.inputSeen = false;
                                    } else {
                                       if (this.phase == 2) {
                                          int slot = this.scan(inv);
                                          if (slot < 0) {
                                             this.phase = 5;
                                             return;
                                          }

                                          if (slot == current) {
                                             InventoryActions.pressCount(c.minecraft().gameSettings.keyBindUseItem, 1);
                                             if (this.rod) {
                                                this.phase = 3;
                                                Entity target = GameHooks.target();
                                                Vec3 targetPos = target == null ? null : TimingAccess.server(target);
                                                this.retract = retractTicks(
                                                   new Vec3(c.player().posX, c.player().posY + 1.62, c.player().posZ), targetPos, this.number("retract_speed")
                                                );
                                                this.timer = this.delay() + this.timer;
                                                return;
                                             }

                                             if (!this.isHoldBinding()) {
                                                this.phase = 5;
                                                this.timer = this.delay() + this.timer;
                                             } else {
                                                this.timer = this.throwingDelay() + this.timer;
                                             }

                                             return;
                                          }

                                          this.phase = 1;
                                          this.selected = slot;
                                          this.timer = this.delay() + this.timer;
                                          break label180;
                                       }

                                       if (this.phase == 3) {
                                          if (this.retract != 0) {
                                             break label148;
                                          }

                                          this.phase = this.previous == this.selected ? 4 : 5;
                                       } else {
                                          if (this.phase == 4) {
                                             inv.currentItem = (this.previous + 1) % 9;
                                             this.phase = 5;
                                             return;
                                          }

                                          if (this.phase == 5) {
                                             this.phase = 6;
                                             this.restore();
                                             this.release();
                                             this.timer = this.delay() + this.timer;
                                             break label148;
                                          }
                                       }
                                    }

                                    this.timer = this.delay() + this.timer;
                                    break label180;
                                 }
                              }
                           }
                        }
                     }
                  }

                  if (this.phase == 6) {
                     if (this.isHoldBinding()) {
                        this.phase = 0;
                     } else {
                        this.modules.requestEnabled(this, false);
                     }
                  }

                  return;
               }

               pass++;
            }
         }
      }
   }

   @Override
   protected strictfp void onDisable(ClientContext c) {
      this.restore();
      this.release();
   }

   @Override
   public strictfp void onWorldUnload(ClientContext c) {
      this.restore();
      this.release();
      this.cancel = false;
      this.phase = 0;
      this.timer = 0.0F;
      this.retract = 0;
      this.inputSeen = false;
   }
}
