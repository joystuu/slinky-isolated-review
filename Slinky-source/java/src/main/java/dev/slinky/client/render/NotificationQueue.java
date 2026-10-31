package dev.slinky.client.render;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final strictfp class NotificationQueue {
   private final NotificationQueue.Message[] records = new NotificationQueue.Message[10];
   private long start;
   private int count;
   private String enabledName;
   private String disabledName;
   private int enabledCount;
   private int disabledCount;

   public strictfp void append(int kind, String text) {
      byte[] source = text.getBytes(StandardCharsets.UTF_8);
      String limited = source.length < 101 ? text : new String(source, 0, 100, StandardCharsets.UTF_8);
      this.records[(int)Long.remainderUnsigned(this.start + (long)this.count, 10L)] = new NotificationQueue.Message(kind, limited);
      if (this.count == 10) {
         this.start++;
      } else {
         this.count++;
      }
   }

   public strictfp void state(String name, boolean enabled) {
      if (enabled) {
         this.enabledName = name;
         this.enabledCount++;
      } else {
         this.disabledName = name;
         this.disabledCount++;
      }
   }

   public strictfp int advance(float elapsed, int oldFlags, boolean hidden) {
      if (hidden) {
         this.count = 0;
         this.enabledCount = this.disabledCount = 0;
         return oldFlags;
      } else {
         float expiry = RenderConstants.f(1384008);

         while (this.count != 0 && this.records[(int)Long.remainderUnsigned(this.start, 10L)].age > expiry) {
            this.start++;
            this.count--;
         }

         if (this.enabledCount != 0) {
            this.append(
               1,
               this.enabledCount == 1
                  ? RenderConstants.text(1384300) + " " + this.enabledName
                  : RenderConstants.text(1384300) + " " + Integer.toUnsignedString(this.enabledCount) + " mods"
            );
            this.enabledCount = 0;
         }

         if (this.disabledCount != 0) {
            this.append(
               1,
               this.disabledCount == 1
                  ? RenderConstants.text(1384308) + " " + this.disabledName
                  : RenderConstants.text(1384308) + " " + Integer.toUnsignedString(this.disabledCount) + " mods"
            );
            this.disabledCount = 0;
         }

         if (this.count == 0) {
            return oldFlags;
         } else {
            boolean transitioning = false;
            double late = RenderConstants.d(1384016);
            double early = RenderConstants.d(1384024);

            for (NotificationQueue.Message m : this.messages()) {
               m.age += elapsed;
               if ((double)m.age > late || (double)m.age < early) {
                  transitioning = true;
               }
            }

            return oldFlags | (transitioning ? 3 : 1);
         }
      }
   }

   public strictfp List<NotificationQueue.Message> messages() {
      List<NotificationQueue.Message> result = new ArrayList<>();

      for (int i = 0; i < this.count; i++) {
         result.add(this.records[(int)Long.remainderUnsigned(this.start + (long)i, 10L)]);
      }

      return Collections.unmodifiableList(result);
   }

   public strictfp void clearMessages() {
      this.count = 0;
   }

   public strictfp void clearAll() {
      this.count = this.enabledCount = this.disabledCount = 0;
   }

   public static strictfp float opacity(float value) {
      double age = (double)value;
      double duration = RenderConstants.d(1384040);
      float amount = RenderConstants.f(1384048);
      if (duration > age) {
         amount = RenderMath.smooth((float)(age / duration));
      } else if (age > RenderConstants.d(1384056)) {
         amount = RenderMath.smooth((float)((RenderConstants.d(1384064) - age) / duration));
      }

      return RenderMath.min(RenderConstants.f(1384048), amount);
   }

   public static final strictfp class Message {
      public final int kind;
      public final String text;
      public float age;

      private Message(int kind, String text) {
         this.kind = kind;
         this.text = text;
      }
   }
}
