package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.RecoveredMath;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.action.ActionConstants;
import dev.slinky.client.numeric.DoubleNumeric;
import dev.slinky.client.numeric.FloatNumeric;
import dev.slinky.client.placement.PlacementFrame;
import dev.slinky.client.placement.PlacementSelection;
import dev.slinky.client.rotation.RecoveredRandom;
import dev.slinky.client.rotation.RotationMath;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;

public final strictfp class BlockInModule extends EvidenceModule {
   private final BlockInModule.State state = new BlockInModule.State();
   private final ModuleManager modules;

   public BlockInModule(ModuleManager modules) {
      super("blockin", "Block In", ModuleCategory.UTILITY);
      this.modules = modules;
   }

   @Override
   public strictfp boolean listens(int type) {
      return type == 0 || type == 5 || type == 13 || type == 16 || type == 18 || type == 26;
   }

   @Override
   public strictfp void onEvent(ClientContext c, SemanticEvent e) {
      PlacementFrame i = new PlacementFrame.Game(c, this.modules, this);
      if (e.type == 26) {
         this.state.clear(i);
         PlacementSelection.release(this);
      } else if (e.type == 5 && e.module == this) {
         if (e.enabled && c.ready()) {
            this.state.enable(c.player().rotationYaw);
         } else if (!e.enabled) {
            this.state.restore(i);
         }
      } else if (e.type == 13 && this.state.correcting) {
         e.active = true;
         e.remapMovement = true;
         if (this.state.movement == 1) {
            e.yaw = this.state.centerYaw;
            e.pitch = this.state.centerPitch;
         }
      } else if (e.type == 16) {
         this.state.input(i);
      } else if (e.type == 18) {
         this.state.update(i, this.number("speed"), this.flag("require_ground"), RecoveredRandom.SHARED, RecoveredRandom.now());
      }
   }

   @Override
   protected strictfp void onDisable(ClientContext c) {
      this.state.clear(new PlacementFrame.Game(c, this.modules, this));
      PlacementSelection.release(this);
   }

   @Override
   public strictfp void onWorldUnload(ClientContext c) {
      this.onDisable(c);
   }

   public static final class State {
      public long placed;
      public int direction;
      public int movement;
      public boolean correcting;
      public boolean keysOwned;
      public float centerYaw;
      public float centerPitch;
      public double previousY;
      private final RecoveredRandom.Smooth pointA = new RecoveredRandom.Smooth();
      private final RecoveredRandom.Smooth pointB = new RecoveredRandom.Smooth();
      private final RecoveredRandom.Smooth sideA = new RecoveredRandom.Smooth();
      private final RecoveredRandom.Smooth sideB = new RecoveredRandom.Smooth();
      private final RecoveredRandom.Smooth speedA = new RecoveredRandom.Smooth();
      private final RecoveredRandom.Smooth speedB = new RecoveredRandom.Smooth();

      private static strictfp float f(int a) {
         return ActionConstants.f(a);
      }

      private static strictfp double d(int a) {
         return ActionConstants.d(a);
      }

      private static strictfp double abs(double value) {
         return Double.longBitsToDouble(Double.doubleToRawLongBits(value) & ActionConstants.longAt(1403024));
      }

      private static strictfp float smooth(RecoveredRandom.Smooth state, int bounds, int period, RecoveredRandom r, double now) {
         return state.sample(f(bounds), f(bounds + 4), d(period), now, r);
      }

      private static strictfp boolean allowed(PlacementFrame i, int x, int y, int z) {
         int type = i.block(x, y, z);
         return Integer.compareUnsigned(type, 31) <= 0 && (-2147482879 >>> (type & 31) & 1) != 0;
      }

      public static strictfp boolean centered(Vec3 self) {
         double x = abs(DoubleNumeric.remainder(self.xCoord, d(1403072)));
         double z = abs(DoubleNumeric.remainder(self.zCoord, d(1403072)));
         return d(1403080) >= x && x >= d(1403088) && z >= d(1403088) && d(1403080) >= z;
      }

      public strictfp void enable(float yaw) {
         this.placed = 0L;
         int n = RecoveredMath.truncateInt(FloatNumeric.round(yaw / f(1402992)));
         int aligned = (n < 0 ? n + 3 : n) & -4;
         this.direction = n - aligned;
      }

      public strictfp void input(PlacementFrame i) {
         if (this.movement != 0) {
            i.movement(this.movement);
            this.keysOwned = true;
         }
      }

      public strictfp void restore(PlacementFrame i) {
         if (this.keysOwned) {
            i.restoreMovement();
            this.keysOwned = false;
         }

         this.correcting = false;
         this.movement = 0;
      }

      private strictfp void cleanup(PlacementFrame i) {
         i.disable();
      }

      public strictfp void update(PlacementFrame i, float speed, boolean requireGround, RecoveredRandom r, double now) {
         this.correcting = false;
         this.movement = 0;
         if (!i.ready()) {
            this.cleanup(i);
         } else {
            boolean ground = i.ground();
            if (requireGround && !ground) {
               this.cleanup(i);
            } else {
               Vec3 self = i.position();
               double selfY = self.yCoord;
               double fx = DoubleNumeric.floor(self.xCoord);
               double fy = DoubleNumeric.floor(selfY) + d(1403008);
               double fz = DoubleNumeric.floor(self.zCoord);
               if (abs(selfY - this.previousY) > d(1403040)) {
                  this.placed = 0L;
               }

               int x = RecoveredMath.truncateInt(fx);
               int y = RecoveredMath.truncateInt(fy);
               int z = RecoveredMath.truncateInt(fz);
               this.previousY = selfY;

               int probe;
               for (probe = 0; probe < 10 && (this.placed >>> probe & 4294967296L) == 0L; probe++) {
                  int offset = 1403776 + probe * 3;
                  if (!allowed(i, x + ActionConstants.byteAt(offset), y + ActionConstants.byteAt(offset + 1), z + ActionConstants.byteAt(offset + 2))) {
                     break;
                  }
               }

               Vec3 target = null;
               int bit = 0;
               if (probe != 0 && probe != 10) {
                  bit = 32 + (probe >= 5 && probe <= 8 ? probe - 4 : 0);
                  int direct = probe >= 5 && probe <= 8 ? probe - 4 : probe;
                  if (direct == 1 || direct == 3) {
                     float yy = smooth(this.pointA, 1403108, 1403064, r, now) + (float)y;
                     int bounds = direct == 1 ? 1403100 : 1403116;
                     float zz = smooth(this.pointB, bounds, 1403064, r, now) + (float)z;
                     target = new Vec3((double)(x + (direct == 3 ? 1 : 0)), (double)yy, (double)zz);
                  } else if (direct != 2 && direct != 4) {
                     float xx = this.pointA.sample(f(1403060), f(1403096), d(1403064), now, r) + (float)x;
                     float zz = this.pointB.sample(f(1403060), f(1403096), d(1403064), now, r) + (float)z;
                     target = new Vec3((double)xx, (double)(y + 4), (double)zz);
                  } else {
                     int bounds = direct == 2 ? 1403116 : 1403100;
                     float xx = smooth(this.pointA, bounds, 1403064, r, now) + (float)x;
                     float yy = smooth(this.pointB, 1403108, 1403064, r, now) + (float)y;
                     target = new Vec3((double)xx, (double)yy, (double)(z + (direct == 4 ? 1 : 0)));
                  }
               } else {
                  for (int attempt = 0; attempt < 4; attempt++) {
                     int dir = this.direction + attempt & 3;
                     int dx = ActionConstants.byteAt(1403806 + dir * 2);
                     int dz = ActionConstants.byteAt(1403807 + dir * 2);
                     int nx = x + dx;
                     int nz = z + dz;
                     AxisAlignedBB box = new AxisAlignedBB((double)nx, (double)y, (double)nz, (double)nx + d(1403816), (double)y + d(1403824), 1.0 + (double)nz);
                     if (!i.box().intersectsWith(box)) {
                        int level = 0;

                        while (
                           level < 3
                              && (((int)this.placed >>> (dir * 3 + level & 31) & 1) != 0 || !allowed(i, nx, y + level, nz) || allowed(i, nx, y + level - 1, nz))
                        ) {
                           level++;
                        }

                        if (level != 3) {
                           int bounds = level != 0 ? 1403056 : 1403048;
                           double half = d(1403064);
                           float a = smooth(this.sideA, bounds, 1403064, r, now);
                           float b = smooth(this.sideB, bounds, 1403064, r, now);
                           target = new Vec3(half - (double)(a * (float)dx) + (double)nx, (double)(y + level), half - (double)(b * (float)dz) + (double)nz);
                           bit = dir * 3 + level;
                           break;
                        }
                     }
                  }

                  if (target == null) {
                     if (ground && centered(self)) {
                        this.cleanup(i);
                     }

                     return;
                  }
               }

               if (!i.claim(500)) {
                  this.cleanup(i);
               } else {
                  i.select(true, true);
                  if (!i.heldBlock()) {
                     this.cleanup(i);
                  } else {
                     float currentYaw = i.yaw() + i.offsetYaw();
                     float currentPitch = i.pitch() + i.offsetPitch();
                     float[] desired = RotationMath.angles(i.eye(), target);
                     float yaw = RotationMath.wrap(desired[0] - currentYaw);
                     float pitch = desired[1] - currentPitch;
                     double half = d(1403064);
                     float factor = (float)((double)speed * half + half);
                     float scaledYaw = yaw * factor;
                     float scaledPitch = pitch * factor;
                     float rx = this.speedA.sample(f(1403124), f(1403120), d(1403128), now, r);
                     float ry = this.speedB.sample(f(1403124), f(1403120), d(1403128), now, r);
                     rx = rx * f(1403136) * speed;
                     ry = f(1403136) * ry * speed;
                     float cx = RotationMath.clamp(Float.intBitsToFloat(Float.floatToRawIntBits(rx) ^ ActionConstants.intAt(1403152)), rx, yaw);
                     float cy = RotationMath.clamp(Float.intBitsToFloat(ActionConstants.intAt(1403152) ^ Float.floatToRawIntBits(ry)), ry, pitch);
                     float resultYaw = (cx - scaledYaw) * f(1403168) + scaledYaw;
                     float resultPitch = (cy - scaledPitch) * f(1403168) + scaledPitch;
                     i.publish(resultYaw, resultPitch);
                     float ax = Float.intBitsToFloat(Float.floatToRawIntBits(yaw - resultYaw) & ActionConstants.intAt(1403184));
                     float ay = Float.intBitsToFloat(Float.floatToRawIntBits(pitch - resultPitch) & ActionConstants.intAt(1403184));
                     if (f(1403200) > ay + ax) {
                        i.press();
                        this.placed |= 1L << (bit & 63);
                     }

                     this.correcting = true;
                     if (centered(self)) {
                        this.movement = 2;
                     } else {
                        Vec3 center = new Vec3(DoubleNumeric.floor(self.xCoord) + half, self.yCoord, DoubleNumeric.floor(self.zCoord) + half);
                        this.movement = 1;
                        float[] rotation = RotationMath.angles(self, center);
                        this.centerYaw = rotation[0];
                        this.centerPitch = rotation[1];
                     }
                  }
               }
            }
         }
      }

      public strictfp void clear(PlacementFrame i) {
         this.restore(i);
         this.placed = 0L;
      }
   }
}
