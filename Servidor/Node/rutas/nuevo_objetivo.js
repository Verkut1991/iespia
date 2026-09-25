const express = require('express');
const router = express.Router();
const { v4: uuidv4 } = require('uuid');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

// Importa el middleware de autenticación desde el módulo de autenticación.
const { autenticar } = require('./autenticacion');

//  Almacenamiento en Memoria 
// Array para guardar los objetivos (caras a buscar) temporalmente.
// Se exporta para que otros módulos puedan acceder a la lista.
let objetivos = [];

//  Rutas de Gestión de Objetivos 

// Ruta protegida para crear un nuevo objetivo a vigilar.
router.post('/', autenticar, (req, res) => {
    const { name, image, priority } = req.body;

    // Validación básica de campos requeridos.
    if (!name || !image) return res.status(400).json({ error: 'Faltan nombre o imagen' });

    const idObjetivo = uuidv4();
    // Limpia el nombre para usarlo en el nombre del archivo (solo letras, números y guiones bajos).
    const nombreSanitizado = name.replace(/[^a-z0-9]/gi, '_').toLowerCase();
    const nombreArchivo = `target_${nombreSanitizado}_${idObjetivo.substring(0, 8)}.jpg`;

    // Ruta donde se guardará la imagen del objetivo.
    const rutaArchivo = path.join(__dirname, '..', 'subidas', nombreArchivo);

    // Guardar imagen en disco.
    try {
        // Elimina la cabecera del Base64 (data:image/...) si existe.
        const datosBase64 = image.replace(/^data:image\/\w+;base64,/, "");
        // Escribe el archivo decodificando el Base64.
        fs.writeFileSync(rutaArchivo, datosBase64, 'base64');
        console.log(` Imagen guardada: ${rutaArchivo}`);
    } catch (err) {
        console.error(` Error guardando imagen: ${err.message}`);
        return res.status(500).json({ error: 'Fallo al guardar la imagen' });
    }

    // Objeto objetivo en memoria.
    const nuevoObjetivo = {
        id: idObjetivo,
        name,
        imagePath: rutaArchivo,
        priority: priority || 'medium',
        active: true, // Activo por defecto.
        createdAt: new Date()
    };
    objetivos.push(nuevoObjetivo); // Añade a la lista en memoria.
    console.log(` Registrado: ${name} (${nuevoObjetivo.id})`);

    // Llama al script de Python para procesar el objetivo y guardar su embedding en MongoDB.
    const rutaPython = process.env.PYTHON_BIN || 'python3';
    // Construye el comando: python procesar_objetivo.py <ruta_imagen> <nombre>
    const comandoPython = `${rutaPython} procesar_objetivo.py "${rutaArchivo}" "${name}"`;

    exec(comandoPython, { cwd: path.join(__dirname, '..') }, (error, stdout, stderr) => {
        if (error) {
            console.error(`[PYTHON] Error de ejecución: ${error.message}`);
            return;
        }
        if (stderr) {
            console.error(`[PYTHON] stderr: ${stderr}`); // Log de errores del script.
        }
        console.log(`[PYTHON] stdout: ${stdout}`); // Log de salida estándar del script.
    });

    res.status(201).json(nuevoObjetivo);
});

// Ruta para listar objetivos activos.
router.get('/', autenticar, (req, res) => {
    const objetivosActivos = objetivos.filter(o => o.active);
    res.json(objetivosActivos);
});

// Ruta para desactivar (borrar lógicamente) un objetivo.
router.delete('/:id', autenticar, (req, res) => {
    const { id } = req.params;
    const objetivo = objetivos.find(o => o.id === id);
    if (!objetivo) return res.status(404).json({ error: 'Objetivo no encontrado' });

    objetivo.active = false;
    console.log(` Desactivado: ${objetivo.name} (${id})`);
    res.json({ message: 'Objetivo desactivado', id });
});

// Exporta el router y la lista de objetivos.
module.exports = { router, objetivos };
