import { create } from 'zustand';

type FlashKey = 'saved' | 'deleted';

interface FlashState {
  message: FlashKey | null;
  show: (message: FlashKey) => void;
  clear: () => void;
}

/** A short success notice shown on the screen the user returns to after saving or deleting. */
export const useFlash = create<FlashState>((set) => ({
  message: null,
  show: (message) => set({ message }),
  clear: () => set({ message: null }),
}));
