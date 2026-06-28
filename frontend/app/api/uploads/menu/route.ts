import { mkdir, writeFile } from "fs/promises";
import path from "path";
import { NextRequest, NextResponse } from "next/server";

const MAX_BYTES = 4 * 1024 * 1024;
const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp", "image/gif"]);

function extensionFor(type: string): string {
  if (type === "image/png") return "png";
  if (type === "image/webp") return "webp";
  if (type === "image/gif") return "gif";
  return "jpg";
}

export async function POST(request: NextRequest) {
  const form = await request.formData();
  const file = form.get("file");

  if (!(file instanceof File)) {
    return NextResponse.json({ message: "Arquivo de imagem obrigatório." }, { status: 400 });
  }
  if (!ALLOWED_TYPES.has(file.type)) {
    return NextResponse.json({ message: "Formato inválido. Use JPG, PNG, WEBP ou GIF." }, { status: 415 });
  }
  if (file.size > MAX_BYTES) {
    return NextResponse.json({ message: "Imagem maior que 4 MB." }, { status: 413 });
  }

  const uploadDir = path.join(process.cwd(), "public", "uploads", "menu");
  await mkdir(uploadDir, { recursive: true });

  const filename = `${Date.now()}-${crypto.randomUUID()}.${extensionFor(file.type)}`;
  const bytes = Buffer.from(await file.arrayBuffer());
  await writeFile(path.join(uploadDir, filename), bytes);

  return NextResponse.json({ url: `/uploads/menu/${filename}` }, { status: 201 });
}
