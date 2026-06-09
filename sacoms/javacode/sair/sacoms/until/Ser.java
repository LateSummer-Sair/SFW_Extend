package sair.sacoms.until;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import sair.sacoms.Urler;

public class Ser {
	private static <T extends Serializable> void SerializePerson(T t, Urler url) throws Exception {
		ObjectOutputStream oo = null;
		try {
			oo = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(new File(url.getUrl()))));
			oo.writeObject(t);
		} catch (Exception e) {
			throw e;
		} finally {
			if (oo != null)
				oo.close();
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Serializable> T DeserializePerson(String url) throws Exception {
		ObjectInputStream ois = null;
		T person = null;
		try {
			ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(new File(url))));
			person = (T) ois.readObject();
		} catch (Exception e) {
			throw e;
		} finally {
			if (ois != null)
				ois.close();
		}
		return person;
	}

	public static <T extends Serializable> T getOser(String url) throws Exception {
		Urler Url = new Urler(url);
		if (!Url.getUrlFound())
			return null;
		T back = null;
		try {
			back = DeserializePerson(Url.getUrl());
		} catch (Exception e) {
			throw e;
		}
		return back;
	}

	public static <T extends Serializable> void setOser(T t, String url) throws Exception {
		Urler Url = new Urler(url);
		if (!Url.getHDFound())
			Url.creatThisHD();
		try {
			SerializePerson(t, Url);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		}
	}
}
