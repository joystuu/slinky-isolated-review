package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.numeric.DoubleTrigonometry;
import dev.slinky.client.render.DrawPlan;
import dev.slinky.client.render.RenderAccess;
import dev.slinky.client.render.RenderConstants;
import dev.slinky.client.render.RenderMath;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityEnderPearl;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;

public final strictfp class IndicatorsModule extends EvidenceModule {
   private final List<IndicatorsModule.Row> rows = new ArrayList<>();

   public IndicatorsModule() {
      super("indicators", "Indicators", ModuleCategory.RENDER);
   }

   @Override
   public strictfp boolean listens(int t) {
      return t == 5 || t == 7 || t == 18;
   }

   @Override
   public strictfp void onEvent(ClientContext c, SemanticEvent e) {
      if (e.type == 5) {
         if (e.module == this && !e.enabled) {
            this.rows.clear();
         }
      } else if (e.type == 18) {
         this.update(c);
      } else if (c.ready()) {
         RenderAccess.Frame frame = RenderAccess.frame(c, e);
         if (frame != null) {
            RenderAccess.submit(e, this.plan(frame, e.timerRenderPartialTicks, c.minecraft().getRenderViewEntity().rotationYaw, RenderAccess.now()));
         }
      }
   }

   private strictfp void update(ClientContext c) {
      this.rows.clear();
      if (c.ready()) {
         double[] self = RenderAccess.position(c.player());

         for (Entity entity : c.world().loadedEntityList) {
            int type = entity instanceof EntityEnderPearl ? 14 : (entity instanceof EntityFireball ? 12 : (entity instanceof EntityArrow ? 10 : 0));
            if (type != 0
               && this.flag(type == 14 ? "show_pearl" : (type == 12 ? "show_fireball" : "show_arrow"))
               && (type != 10 || !RenderAccess.arrowInGround((EntityArrow)entity))) {
               double distance = RenderMath.distanceSquared(RenderAccess.position(entity), self);
               if (!this.flag("require_coming_closer") || !(distance > RenderMath.distanceSquared(RenderAccess.previous(entity), self))) {
                  distance = StrictMath.sqrt(distance);
                  String label = String.format(Locale.ROOT, RenderConstants.text(1392808), distance);
                  if (label.length() > 9) {
                     label = label.substring(0, 9);
                  }

                  int color = distance > RenderConstants.d(1392680) ? -256 : -65536;
                  if (distance > RenderConstants.d(1392688)) {
                     color = -1;
                  }

                  this.rows.add(new IndicatorsModule.Row(entity, type, label, (int)RenderAccess.width(c, label), color));
                  if (this.rows.size() == 10) {
                     break;
                  }
               }
            }
         }
      }
   }

   public strictfp DrawPlan plan(RenderAccess.Frame frame, float partial, float yaw, double clock) {
      float one = RenderConstants.f(1392612);
      double phase = clock * RenderConstants.d(1392616);
      double half = RenderConstants.d(1392624);
      float alpha = (float)(DoubleTrigonometry.sine(phase) * half + half);
      DrawPlan p = new DrawPlan()
         .push()
         .translate(frame.width * RenderConstants.f(1392608), frame.height * RenderConstants.f(1392608), 0.0F)
         .lineWidth(one)
         .enable(2848);

      for (IndicatorsModule.Row r : this.rows) {
         double x = (double)RenderMath.lerp((float)r.px, (float)r.x, partial) - frame.camera[0];
         double z = (double)RenderMath.lerp((float)r.pz, (float)r.z, partial) - frame.camera[2];
         float angle = PointersModule.angle(x, z, yaw, 1392632, 1392640, 1392648);
         float size = this.number("scale");
         p.push()
            .rotate(angle, 0.0F, 0.0F, one)
            .translate(0.0F, Float.intBitsToFloat(Float.floatToRawIntBits(this.number("radius")) ^ RenderConstants.i(1392656)), 0.0F)
            .scale(size, size, size)
            .push()
            .rotate(Float.intBitsToFloat(Float.floatToRawIntBits(angle) ^ RenderConstants.i(1392656)), 0.0F, 0.0F, one)
            .text(r.label, (float)((double)(-r.width) * half), -5.0F, r.color, true, 9.0F)
            .pop()
            .translate(0.0F, RenderConstants.f(1392672), 0.0F)
            .scale(RenderConstants.f(1392676), RenderConstants.f(1392676), RenderConstants.f(1392676));
         int type = r.type - 10;
         p.color(RenderConstants.f(1392912 + 4 * type), RenderConstants.f(1392932 + 4 * type), RenderConstants.f(1392952 + 4 * type), alpha)
            .graphics(169280)
            .color(0.0F, 0.0F, 0.0F, alpha)
            .graphics(169328)
            .pop();
      }

      return p.pop();
   }

   public strictfp List<IndicatorsModule.Row> rows() {
      return Collections.unmodifiableList(this.rows);
   }

   @Override
   protected strictfp void onDisable(ClientContext c) {
      this.rows.clear();
   }

   @Override
   public strictfp void onWorldUnload(ClientContext c) {
      this.rows.clear();
   }

   public static final strictfp class Row {
      public final double x;
      public final double z;
      public final double px;
      public final double pz;
      public final int type;
      public final int color;
      public final int width;
      public final String label;

      public Row(Entity e, int type, String label, int width, int color) {
         this(e.posX, e.posZ, e.prevPosX, e.prevPosZ, type, label, width, color);
      }

      public Row(double x, double z, double px, double pz, int type, String label, int width, int color) {
         this.x = x;
         this.z = z;
         this.px = px;
         this.pz = pz;
         this.type = type;
         this.label = label;
         this.width = width;
         this.color = color;
      }
   }
}
