import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import tseslint from "typescript-eslint";

export default defineConfig([
  globalIgnores([
    "android/**",
    "ios/**",
    "babel.config.js",
    "node_modules/**",
    "coverage/**",
  ]),
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ["*.js", "*.config.js"],
    languageOptions: {
      globals: {
        __dirname: "readonly",
        global: "readonly",
        module: "readonly",
        require: "readonly",
      },
    },
    rules: {
      "@typescript-eslint/no-require-imports": "off",
    },
  },
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      parserOptions: {
        ecmaFeatures: {
          jsx: true,
        },
      },
    },
  },
]);
