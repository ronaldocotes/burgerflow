const nextJest = require("next/jest");

// next/jest cuida do transform (SWC), do alias "@/..." (tsconfig paths) e do CSS mock.
const createJestConfig = nextJest({ dir: "./" });

/** @type {import('jest').Config} */
const config = {
  testEnvironment: "jsdom",
  passWithNoTests: true,
  setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
  modulePathIgnorePatterns: ["<rootDir>/.next/"],
  testPathIgnorePatterns: [
    "<rootDir>/.next/",
    "<rootDir>/node_modules/",
    "<rootDir>/tests/e2e/",
  ],
};

module.exports = createJestConfig(config);
