package dev.slinky.client.placement;

import dev.slinky.client.ClientContext;
import dev.slinky.client.ModuleActionService;
import dev.slinky.client.action.ActionAccess;
import dev.slinky.client.inventory.InventoryActions;
import java.util.function.IntUnaryOperator;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

public final class PlacementSelection {
   private static int preferred;
   private static boolean startAfterCurrent;
   private static Object selectionOwner;
   private static PlacementSelection.Inventory inventoryOwner;
   private static Object restorationOwner;
   private static int originalSlot;
   private static int inactivity;
   private static int capturedType;
   private static int capturedCount;
   private static int lastSlot;
   private static int repetitions;

   private PlacementSelection() {
   }

   public static synchronized void remember(int type, boolean afterCurrent) {
      preferred = type;
      startAfterCurrent = afterCurrent;
   }

   public static PlacementSelection.Inventory inventory(final InventoryPlayer player) {
      return new PlacementSelection.Inventory() {
         @Override
         public int current() {
            return player.currentItem;
         }

         @Override
         public void current(int slot) {
            player.currentItem = slot;
         }

         private ItemStack at(int slot) {
            return player.getStackInSlot(slot);
         }

         @Override
         public boolean stack(int slot) {
            return this.at(slot) != null;
         }

         @Override
         public boolean item(int slot) {
            return this.at(slot) != null && this.at(slot).getItem() != null;
         }

         @Override
         public int type(int slot) {
            return InventoryActions.type(this.at(slot));
         }

         @Override
         public boolean block(int slot) {
            return ActionAccess.isBlock(this.at(slot));
         }

         @Override
         public int count(int slot) {
            return this.at(slot).stackSize;
         }
      };
   }

   public static synchronized void frameReset() {
      if (selectionOwner != null) {
         ModuleActionService.release(selectionOwner);
      }

      selectionOwner = null;
      inactivity++;
      preferred = 0;
      startAfterCurrent = false;
   }

   public static synchronized void capture(PlacementSelection.Inventory i) {
      if (capturedType == 0) {
         int slot = i.current();
         if (i.stack(slot) && i.block(slot)) {
            capturedType = i.type(slot);
            capturedCount = i.count(slot);
            if (Integer.compareUnsigned(inactivity, 20) > 0 || lastSlot != slot) {
               repetitions = capturedCount;
            }

            inactivity = 0;
            lastSlot = slot;
         }
      }
   }

   public static void capture(ClientContext c) {
      if (c.ready()) {
         capture(inventory(c.player().inventory));
      }
   }

   public static synchronized void consume(PlacementSelection.Inventory i) {
      if (capturedType != 0) {
         int slot = i.current();
         boolean depleted = !i.stack(slot) || !i.item(slot) || i.type(slot) == 0;
         if (!depleted && i.count(slot) < capturedCount) {
            depleted = --repetitions == 0;
         }

         if (depleted) {
            preferred = capturedType;
            startAfterCurrent = true;
         }

         capturedType = 0;
      }
   }

   public static void consume(ClientContext c) {
      if (c.ready()) {
         consume(inventory(c.player().inventory));
      }
   }

   public static synchronized int preferred() {
      return preferred;
   }

   public static synchronized boolean afterCurrent() {
      return startAfterCurrent;
   }

   public static int bridgeRank(int id) {
      switch (id) {
         case 1:
         case 2:
         case 3:
         case 35:
         case 45:
            return 2;
         case 5:
         case 17:
         case 20:
         case 24:
         case 87:
            return 1;
         default:
            int offset = id - 121;
            return Integer.compareUnsigned(offset, 41) <= 0 && (2473901162497L >>> (offset & 63) & 1L) != 0L ? 1 : -1;
      }
   }

   public static int blockInRank(int id) {
      switch (id) {
         case 1:
         case 2:
         case 3:
         case 20:
         case 24:
         case 45:
         case 87:
         case 159:
            return 2;
         case 4:
         case 5:
         case 17:
         case 121:
         case 162:
            return 3;
         case 35:
            return 1;
         case 49:
            return 4;
         default:
            return -1;
      }
   }

   public static int choose(int[] ids, int current, int preferredId, boolean afterCurrent, IntUnaryOperator rank) {
      int first = current + (afterCurrent ? 1 : 0);
      int chosen = -1;
      int best = -1;

      for (int n = 0; n < 9; n++) {
         int slot = (first + n) % 9;
         int id = ids[slot];
         if (id != 0) {
            if (preferredId != 0 && id == preferredId) {
               return slot;
            }

            int score = rank.applyAsInt(id);
            if (score > best) {
               best = score;
               chosen = slot;
            }
         }
      }

      return chosen;
   }

   public static synchronized boolean select(ClientContext c, Object token, boolean force, boolean blockIn) {
      return selectionOwner == null && c.ready() && (force || preferred != 0) ? select(inventory(c.player().inventory), token, force, blockIn) : false;
   }

   public static synchronized boolean select(PlacementSelection.Inventory inventory, Object token, boolean force, boolean blockIn) {
      if (selectionOwner == null && (force || preferred != 0)) {
         int[] ids = new int[9];

         for (int i = 0; i < 9; i++) {
            ids[i] = inventory.type(i);
         }

         int chosen = choose(ids, inventory.current(), preferred, startAfterCurrent, blockIn ? PlacementSelection::blockInRank : PlacementSelection::bridgeRank);
         if (chosen != -1 && ModuleActionService.claim(token)) {
            if (restorationOwner != token) {
               originalSlot = inventory.current();
               inventoryOwner = inventory;
               restorationOwner = token;
            }

            selectionOwner = token;
            inventory.current(chosen);
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static synchronized void release(Object token) {
      if (restorationOwner == token) {
         inventoryOwner.current(originalSlot);
         restorationOwner = null;
         inventoryOwner = null;
      }

      if (selectionOwner == token) {
         selectionOwner = null;
      }

      ModuleActionService.release(token);
   }

   public static synchronized void reset() {
      if (inventoryOwner != null) {
         inventoryOwner.current(originalSlot);
      }

      if (selectionOwner != null) {
         ModuleActionService.release(selectionOwner);
      }

      restorationOwner = null;
      selectionOwner = null;
      inventoryOwner = null;
      preferred = 0;
      startAfterCurrent = false;
      repetitions = 0;
      lastSlot = 0;
      capturedCount = 0;
      capturedType = 0;
      inactivity = 0;
   }

   public interface Inventory {
      int current();

      void current(int var1);

      boolean stack(int var1);

      boolean item(int var1);

      int type(int var1);

      boolean block(int var1);

      int count(int var1);
   }
}
