package dev.slinky.client.hook;

public final strictfp class UiFrameDriver {
   public static final double INTERVAL = Double.longBitsToDouble(4575957461383581969L);
   private double previous;
   private int resizeFrames;
   private boolean visible;
   private boolean previouslyChanging;

   public UiFrameDriver(double previous, int resizeFrames, boolean visible, boolean previouslyChanging) {
      this.previous = previous;
      this.resizeFrames = resizeFrames;
      this.visible = visible;
      this.previouslyChanging = previouslyChanging;
   }

   public strictfp void frame(UiFrameDriver.Surface surface, int width, int height, double scale, double now) {
      if (this.previous < 0.0) {
         this.previous = now;
      }

      if (surface.resize(width, height)) {
         this.resizeFrames = 10;
      } else if (this.resizeFrames > 0) {
         this.resizeFrames--;
      }

      surface.scale(scale);
      double elapsed = now - this.previous;
      if (elapsed > INTERVAL) {
         float frameElapsed = (float)elapsed;
         int flags = surface.update(frameElapsed);
         this.visible = (flags & 1) != 0;
         boolean changing = (flags & 2) != 0;
         if (this.visible && (changing || this.previouslyChanging || this.resizeFrames > 0)) {
            surface.begin(now);

            try {
               surface.draw(frameElapsed);
            } finally {
               surface.end();
            }

            this.previouslyChanging = changing;
         }

         this.previous = now;
      }

      if (this.visible) {
         surface.present(now);
      } else {
         this.previouslyChanging = true;
      }
   }

   public strictfp double previousSeconds() {
      return this.previous;
   }

   public strictfp int resizeFrames() {
      return this.resizeFrames;
   }

   public strictfp boolean visible() {
      return this.visible;
   }

   public strictfp boolean previouslyChanging() {
      return this.previouslyChanging;
   }

   public interface Surface {
      boolean resize(int var1, int var2);

      void scale(double var1);

      int update(float var1);

      void begin(double var1);

      void draw(float var1);

      void end();

      void present(double var1);
   }
}
